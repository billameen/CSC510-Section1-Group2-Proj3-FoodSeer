package FoodSeer.live;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** A minimal Server-Sent Events client for tests, reading real HTTP. */
final class SseClient implements AutoCloseable {

    /** One received event. */
    record Event(Long id, String name, String data, long receivedAtNanos) {
    }


    final int                       status;
    final BlockingQueue<Event>      events = new LinkedBlockingQueue<>();
    private final InputStream       body;
    private final Thread            reader;

    private SseClient ( final int status, final InputStream body ) {
        this.status = status;
        this.body = body;
        this.reader = new Thread( this::read, "sse-test-reader" );
        this.reader.setDaemon( true );
        if ( status == 200 ) {
            reader.start();
        }
    }

    static SseClient open ( final String url ) throws Exception {
        // Its own client, so closing this stream can't disturb other connections.
        final HttpClient http = HttpClient.newBuilder().version( HttpClient.Version.HTTP_1_1 )
                .connectTimeout( Duration.ofSeconds( 5 ) ).build();
        final HttpResponse<InputStream> response = http.send( HttpRequest.newBuilder( URI.create( url ) )
                .header( "Accept", "text/event-stream" ).GET().build(), HttpResponse.BodyHandlers.ofInputStream() );
        return new SseClient( response.statusCode(), response.body() );
    }

    private void read () {
        try ( BufferedReader lines = new BufferedReader( new InputStreamReader( body, StandardCharsets.UTF_8 ) ) ) {
            Long id = null;
            String name = "message";
            final StringBuilder data = new StringBuilder();
            String line;
            while ( ( line = lines.readLine() ) != null ) {
                if ( line.isEmpty() ) {
                    if ( data.length() > 0 ) {
                        events.add( new Event( id, name, data.toString(), System.nanoTime() ) );
                    }
                    id = null;
                    name = "message";
                    data.setLength( 0 );
                }
                else if ( line.startsWith( "id:" ) ) {
                    id = Long.valueOf( line.substring( 3 ).trim() );
                }
                else if ( line.startsWith( "event:" ) ) {
                    name = line.substring( 6 ).trim();
                }
                else if ( line.startsWith( "data:" ) ) {
                    data.append( line.substring( 5 ).trim() );
                }
            }
        }
        catch ( final Exception e ) {
            // Closed by the test or the server.
        }
    }

    /** Waits for the next event matching a test, skipping others. */
    Event await ( final Predicate<Event> test, final long timeoutMs ) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while ( true ) {
            final long left = deadline - System.currentTimeMillis();
            if ( left <= 0 ) {
                return null;
            }
            final Event event = events.poll( left, TimeUnit.MILLISECONDS );
            if ( event != null && test.test( event ) ) {
                return event;
            }
        }
    }

    /** Everything received within a window. */
    List<Event> drain ( final long windowMs ) throws InterruptedException {
        final List<Event> out = new ArrayList<>();
        final long deadline = System.currentTimeMillis() + windowMs;
        long left;
        while ( ( left = deadline - System.currentTimeMillis() ) > 0 ) {
            final Event event = events.poll( left, TimeUnit.MILLISECONDS );
            if ( event != null ) {
                out.add( event );
            }
        }
        return out;
    }

    @Override
    public void close () {
        try {
            body.close();
        }
        catch ( final Exception ignored ) {
            // Already closed.
        }
        reader.interrupt();
    }
}
