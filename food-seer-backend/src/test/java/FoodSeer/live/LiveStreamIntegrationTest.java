package FoodSeer.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import FoodSeer.entity.Food;
import FoodSeer.entity.User;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.OrderEventRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.repositories.OutboxEventRepository;
import FoodSeer.repositories.UserRepository;

/**
 * The live stream over real HTTP.
 *
 * Scheduling stays off, as in every test context: Spring keeps contexts alive
 * for the whole run and they share one database, so a context with a live
 * dispatcher would keep sending (and stamping) other tests' messages after
 * this class finished. Instead this test runs the dispatcher every 200 ms and
 * the heartbeat every 500 ms on its own thread, stopped after each test.
 */
@SpringBootTest ( webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT )
class LiveStreamIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newBuilder().version( HttpClient.Version.HTTP_1_1 ).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int                   port;
    @Autowired
    private UserRepository        userRepository;
    @Autowired
    private FoodRepository        foodRepository;
    @Autowired
    private OrderRepository       orderRepository;
    @Autowired
    private OrderEventRepository  events;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private StreamBroker          broker;
    @Autowired
    private OutboxDispatcher      dispatcher;

    private ScheduledExecutorService jobs;
    @Autowired
    private PasswordEncoder       passwordEncoder;

    private final String          run       = randomLetters();
    private final List<Long>      orderIds  = new ArrayList<>();
    private final List<SseClient> streams   = new ArrayList<>();
    private Food                  food;
    private String                customer;
    private String                bystander;
    private String                driver;
    private String                staff;

    private static String randomLetters () {
        final Random random = new Random();
        final StringBuilder out = new StringBuilder();
        for ( int i = 0; i < 6; i++ ) {
            out.append( (char) ( 'a' + random.nextInt( 26 ) ) );
        }
        return out.toString();
    }

    @BeforeEach
    void setUp () throws Exception {
        jobs = Executors.newScheduledThreadPool( 2 );
        jobs.scheduleWithFixedDelay( () -> quietly( dispatcher::dispatch ), 0, 200, TimeUnit.MILLISECONDS );
        jobs.scheduleWithFixedDelay( () -> quietly( broker::heartbeat ), 500, 500, TimeUnit.MILLISECONDS );
        food = foodRepository.save( new Food( "LIVE SOUP " + run.toUpperCase(), 50, 5, new ArrayList<>() ) );
        customer = register( "livecust", "CUSTOMER" );
        bystander = register( "livebystander", "CUSTOMER" );
        driver = register( "livedriver", "DRIVER" );
        userRepository.save( User.builder().username( "livestaff" + run ).email( "livestaff" + run + "@example.test" )
                .password( passwordEncoder.encode( "password1" ) ).role( "ROLE_STAFF" ).build() );
        staff = login( "livestaff" + run );
    }

    @AfterEach
    void tearDown () throws InterruptedException {
        streams.forEach( SseClient::close );
        // Let the heartbeat notice the closed streams, then stop both jobs.
        Thread.sleep( 600 );
        jobs.shutdownNow();
        orderIds.forEach( id -> {
            outbox.deleteAll( outbox.findByOrderIdOrderByIdAsc( id ) );
            events.deleteAll( events.findByOrderIdOrderByIdAsc( id ) );
        } );
        orderRepository.deleteAllById( orderIds );
        foodRepository.delete( food );
    }

    private static void quietly ( final Runnable job ) {
        try {
            job.run();
        }
        catch ( final RuntimeException e ) {
            // Retried on the next tick, like the real schedule.
        }
    }

    // ---- HTTP helpers ----

    private String url ( final String path ) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> call ( final String method, final String path, final String body,
            final String token ) throws Exception {
        final HttpRequest.Builder request = HttpRequest.newBuilder( URI.create( url( path ) ) )
                .header( "Content-Type", "application/json" )
                .method( method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString( body ) );
        if ( token != null ) {
            request.header( "Authorization", "Bearer " + token );
        }
        try {
            return HTTP.send( request.build(), HttpResponse.BodyHandlers.ofString() );
        }
        catch ( final java.io.IOException stalePooledConnection ) {
            // The JDK client can hand out a kept-alive connection the server
            // already closed; one retry on a fresh connection is enough.
            return HTTP.send( request.build(), HttpResponse.BodyHandlers.ofString() );
        }
    }

    private String register ( final String name, final String role ) throws Exception {
        final String username = name + run;
        final HttpResponse<String> response = call( "POST", "/auth/register",
                "{\"username\":\"" + username + "\",\"email\":\"" + username + "@example.test\","
                        + "\"password\":\"password1\",\"role\":\"" + role + "\"}", null );
        assertEquals( 200, response.statusCode(), response.body() );
        return login( username );
    }

    private String login ( final String username ) throws Exception {
        final HttpResponse<String> response = call( "POST", "/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"password1\"}", null );
        assertEquals( 200, response.statusCode(), response.body() );
        return JSON.readTree( response.body() ).get( "accessToken" ).asText();
    }

    private String ticket ( final String token ) throws Exception {
        final HttpResponse<String> response = call( "POST", "/api/stream/ticket", null, token );
        assertEquals( 200, response.statusCode(), response.body() );
        return JSON.readTree( response.body() ).get( "ticket" ).asText();
    }

    private SseClient connect ( final String token, final Long lastEventId ) throws Exception {
        final SseClient stream = SseClient.open( url( "/api/stream?ticket=" + ticket( token )
                + ( lastEventId == null ? "" : "&lastEventId=" + lastEventId ) ) );
        streams.add( stream );
        assertEquals( 200, stream.status );
        assertNotNull( stream.await( e -> "ready".equals( e.name() ), 2000 ), "the stream says hello" );
        return stream;
    }

    private Long placeOrder () throws Exception {
        final HttpResponse<String> response = call( "POST", "/api/orders",
                "{\"name\":\"Live\",\"foods\":[{\"id\":" + food.getId() + "}]}", customer );
        assertEquals( 200, response.statusCode(), response.body() );
        final Long id = JSON.readTree( response.body() ).get( "id" ).asLong();
        orderIds.add( id );
        return id;
    }

    private static JsonNode data ( final SseClient.Event event ) throws Exception {
        return JSON.readTree( event.data() );
    }

    private static boolean isAbout ( final SseClient.Event event, final Long orderId, final String what ) {
        try {
            return "order.event".equals( event.name() ) && data( event ).get( "orderId" ).asLong() == orderId
                    && what.equals( data( event ).get( "event" ).asText() );
        }
        catch ( final Exception e ) {
            return false;
        }
    }

    // ---- tests ----

    /** Acceptance criterion: the customer hears a claim within 1 s; another customer hears nothing. */
    @Test
    void theCustomerSeesTheClaimWithinASecondAndNobodyElseDoes () throws Exception {
        final SseClient mine = connect( customer, null );
        final SseClient theirs = connect( bystander, null );
        final Long id = placeOrder();
        assertNotNull( mine.await( e -> isAbout( e, id, "PLACED" ), 2000 ) );

        final long claimedAt = System.nanoTime();
        assertEquals( 200, call( "POST", "/api/orders/" + id + "/claim", null, driver ).statusCode() );
        final SseClient.Event claimed = mine.await( e -> isAbout( e, id, "CLAIMED" ), 1000 );

        assertNotNull( claimed, "the claim arrived within 1 s" );
        assertEquals( "Claimed", data( claimed ).get( "status" ).asText() );
        assertEquals( "livedriver" + run, data( claimed ).get( "driverUsername" ).asText() );
        assertNotNull( claimed.id(), "every message carries an id to resume from" );
        assertTrue( ( claimed.receivedAtNanos() - claimedAt ) / 1_000_000 < 1000 );
        assertTrue( theirs.drain( 600 ).stream().noneMatch( e -> "order.event".equals( e.name() ) ),
                "another customer hears nothing about this order" );
    }

    @Test
    void driversSeeNewOrdersAndClaimsButNotTheRest () throws Exception {
        final String otherDriver = register( "otherdriver", "DRIVER" );
        final SseClient watching = connect( otherDriver, null );
        final Long id = placeOrder();
        assertNotNull( watching.await( e -> isAbout( e, id, "PLACED" ), 2000 ), "a new order to claim" );

        call( "POST", "/api/orders/" + id + "/claim", null, driver );
        assertNotNull( watching.await( e -> isAbout( e, id, "CLAIMED" ), 2000 ), "it left the pool" );

        assertEquals( 200, call( "POST", "/api/orders/" + id + "/cancel", null, staff ).statusCode() );
        assertTrue( watching.drain( 600 ).stream().noneMatch( e -> isAbout( e, id, "CANCELLED" ) ),
                "once claimed, other drivers stop hearing about it" );
    }

    @Test
    void staffSeeEveryOrder () throws Exception {
        final SseClient board = connect( staff, null );
        final Long id = placeOrder();

        assertNotNull( board.await( e -> isAbout( e, id, "PLACED" ), 2000 ) );
    }

    /** Acceptance criterion: reconnecting with Last-Event-ID replays exactly what was missed, in order. */
    @Test
    void reconnectingWithTheLastEventIdReplaysExactlyWhatWasMissed () throws Exception {
        final SseClient first = connect( customer, null );
        final Long warmup = placeOrder();
        final SseClient.Event last = first.await( e -> isAbout( e, warmup, "PLACED" ), 2000 );
        assertNotNull( last );
        final Set<Long> seen = new HashSet<>();
        seen.add( last.id() );
        first.close();

        final Long a = placeOrder();
        call( "POST", "/api/orders/" + a + "/claim", null, driver );
        final Long b = placeOrder();
        // Let the dispatcher send all three before reconnecting.
        Thread.sleep( 800 );

        final SseClient resumed = connect( customer, last.id() );
        final List<String> missed = new ArrayList<>();
        for ( final SseClient.Event e : resumed.drain( 800 ) ) {
            // Like the browser hook: drop anything already seen (the replay
            // starts a little early to cover out-of-order commits).
            if ( "order.event".equals( e.name() ) && e.id() > 0 && seen.add( e.id() )
                    && orderIds.contains( data( e ).get( "orderId" ).asLong() ) ) {
                missed.add( data( e ).get( "orderId" ).asLong() + ":" + data( e ).get( "event" ).asText() );
            }
        }

        assertEquals( List.of( a + ":PLACED", a + ":CLAIMED", b + ":PLACED" ), missed );
    }

    /** Acceptance criterion: reuse, no ticket, and a 4th stream are all refused. */
    @Test
    void ticketsAreSingleUseAndStreamsAreCapped () throws Exception {
        final String once = ticket( customer );
        try ( SseClient ok = SseClient.open( url( "/api/stream?ticket=" + once ) ) ) {
            assertEquals( 200, ok.status );
            try ( SseClient again = SseClient.open( url( "/api/stream?ticket=" + once ) ) ) {
                assertEquals( 401, again.status, "a used ticket is refused" );
            }
        }
        try ( SseClient none = SseClient.open( url( "/api/stream" ) ) ) {
            assertEquals( 401, none.status );
        }
        assertEquals( 401, call( "POST", "/api/stream/ticket", null, null ).statusCode(),
                "a ticket needs a signed-in user" );
        // Let the closed stream above be noticed before counting.
        Thread.sleep( 700 );

        connect( bystander, null );
        connect( bystander, null );
        connect( bystander, null );
        try ( SseClient fourth = SseClient.open( url( "/api/stream?ticket=" + ticket( bystander ) ) ) ) {
            assertEquals( 429, fourth.status );
        }
    }

    @Test
    void heartbeatsNoticeClosedStreamsAndFreeTheirSlot () throws Exception {
        // Streams other tests closed are dropped on the next heartbeat.
        waitForOpenStreams( 0 );
        final int before = broker.openStreams();
        final SseClient stream = connect( customer, null );
        assertEquals( before + 1, broker.openStreams() );

        stream.close();
        waitForOpenStreams( before );

        assertEquals( before, broker.openStreams() );
    }

    private void waitForOpenStreams ( final int count ) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5000;
        while ( broker.openStreams() > count && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 100 );
        }
    }

    /**
     * Open streams must not each hold a database connection: the pool has
     * 10, so 15 streams would freeze every other request if they did.
     */
    @Test
    void manyOpenStreamsDontStarveTheDatabase () throws Exception {
        final List<String> tokens = new ArrayList<>();
        for ( int i = 0; i < 5; i++ ) {
            tokens.add( register( "crowd" + (char) ( 'a' + i ), "CUSTOMER" ) );
        }
        for ( final String token : tokens ) {
            for ( int i = 0; i < 3; i++ ) {
                connect( token, null );
            }
        }

        final long started = System.currentTimeMillis();
        final Long id = placeOrder();
        assertEquals( 200, call( "GET", "/api/orders/" + id, null, customer ).statusCode() );
        assertTrue( System.currentTimeMillis() - started < 2000, "requests still get a connection right away" );
    }
}
