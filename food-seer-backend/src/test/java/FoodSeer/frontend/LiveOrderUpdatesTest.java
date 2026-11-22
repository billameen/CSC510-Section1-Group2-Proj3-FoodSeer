package FoodSeer.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import FoodSeer.dto.NewOrderRequest;
import FoodSeer.entity.DriverStats;
import FoodSeer.live.OutboxDispatcher;
import FoodSeer.entity.Food;
import FoodSeer.entity.User;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.repositories.FoodRepository;
import FoodSeer.repositories.OrderEventRepository;
import FoodSeer.repositories.OrderRepository;
import FoodSeer.repositories.OutboxEventRepository;
import FoodSeer.repositories.UserRepository;
import FoodSeer.service.OrderService;
import io.github.bonigarcia.wdm.WebDriverManager;

/**
 * Milestone 6 acceptance test with two real browsers: the driver claims,
 * picks up and delivers while the customer's My Orders page follows each step
 * without being reloaded. Needs the frontend on :3000, like the other
 * Selenium tests.
 *
 * It uses the same Spring configuration as the other Selenium tests so they
 * share one server on port 8080 (a second configuration would start a second
 * server on the same port). Scheduling is off in tests, so this test runs the
 * outbox dispatcher itself, every 200 ms like the real schedule.
 */
@SpringBootTest ( webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT )
@AutoConfigureMockMvc
class LiveOrderUpdatesTest {

    private static final String  BASE_URL = "http://localhost:3000/";
    private static final String  CUSTOMER = "livecustomerui";
    private static final String  DRIVER   = "livedriverui";

    @Autowired
    private UserRepository        userRepository;
    @Autowired
    private DriverStatsRepository driverStatsRepository;
    @Autowired
    private FoodRepository        foodRepository;
    @Autowired
    private OrderRepository       orderRepository;
    @Autowired
    private OrderEventRepository  events;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private OrderService          orderService;
    @Autowired
    private PasswordEncoder       passwordEncoder;
    @Autowired
    private OutboxDispatcher      dispatcher;

    private ScheduledExecutorService dispatching;

    private final List<ChromeDriver> browsers = new ArrayList<>();
    private Food                     food;
    private Long                     orderId;

    @BeforeEach
    void setUp () {
        // CI downloads a matching chromedriver; a machine that already has one
        // can point at it with -Dwebdriver.chrome.driver=...
        if ( System.getProperty( "webdriver.chrome.driver" ) == null ) {
            WebDriverManager.chromedriver().setup();
        }
        cleanUp();
        userRepository.save( User.builder().username( CUSTOMER ).email( CUSTOMER + "@test.com" )
                .password( passwordEncoder.encode( "password123" ) ).role( "ROLE_CUSTOMER" )
                .costPreference( "MODERATE" ).dailyCalorieGoal( 2000 ).dietaryRestrictions( "" ).build() );
        userRepository.save( User.builder().username( DRIVER ).email( DRIVER + "@test.com" )
                .password( passwordEncoder.encode( "password123" ) ).role( "ROLE_DRIVER" ).build() );
        final DriverStats stats = new DriverStats();
        stats.setUsername( DRIVER );
        driverStatsRepository.save( stats );
        food = foodRepository.save( new Food( "LIVE UI SOUP", 20, 6, new ArrayList<>() ) );

        SecurityContextHolder.getContext().setAuthentication( new UsernamePasswordAuthenticationToken( CUSTOMER,
                null, List.of( new SimpleGrantedAuthority( "ROLE_CUSTOMER" ) ) ) );
        orderId = orderService.createOrder( NewOrderRequest.of( "Live UI order", List.of( food.getId() ) ) ).getId();
        SecurityContextHolder.clearContext();

        dispatching = Executors.newSingleThreadScheduledExecutor();
        dispatching.scheduleWithFixedDelay( () -> {
            try {
                dispatcher.dispatch();
            }
            catch ( final RuntimeException e ) {
                // Retried on the next tick, like the real schedule.
            }
        }, 0, 200, TimeUnit.MILLISECONDS );
    }

    @AfterEach
    void tearDown () {
        dispatching.shutdownNow();
        browsers.forEach( ChromeDriver::quit );
        cleanUp();
    }

    private void cleanUp () {
        userRepository.findByUsername( CUSTOMER ).ifPresent( customer -> {
            orderRepository.findByUser( customer ).forEach( order -> {
                outbox.deleteAll( outbox.findByOrderIdOrderByIdAsc( order.getId() ) );
                events.deleteAll( events.findByOrderIdOrderByIdAsc( order.getId() ) );
                orderRepository.delete( order );
            } );
            userRepository.delete( customer );
        } );
        driverStatsRepository.findByUsername( DRIVER ).ifPresent( driverStatsRepository::delete );
        userRepository.findByUsername( DRIVER ).ifPresent( userRepository::delete );
        if ( food != null ) {
            foodRepository.deleteById( food.getId() );
        }
    }

    /** A separate browser, so each user has their own login. */
    private ChromeDriver browser () {
        final ChromeOptions options = new ChromeOptions();
        options.addArguments( "--no-sandbox", "--disable-dev-shm-usage", "--headless" );
        final ChromeDriver browser = new ChromeDriver( options );
        browsers.add( browser );
        return browser;
    }

    private static WebDriverWait waitFor ( final ChromeDriver browser ) {
        return new WebDriverWait( browser, Duration.ofSeconds( 10 ) );
    }

    private static void logIn ( final ChromeDriver browser, final String username, final String urlPart ) {
        browser.get( BASE_URL );
        waitFor( browser ).until( ExpectedConditions.visibilityOfElementLocated( By.id( "username" ) ) )
                .sendKeys( username );
        browser.findElement( By.id( "password" ) ).sendKeys( "password123" );
        browser.findElement( By.cssSelector( "button[type='submit']" ) ).click();
        waitFor( browser ).until( ExpectedConditions.urlContains( urlPart ) );
    }

    private WebElement customerCard ( final ChromeDriver customer ) {
        return customer.findElements( By.cssSelector( ".order-card" ) ).stream()
                .filter( card -> card.getText().contains( "Live UI order" ) ).findFirst().orElse( null );
    }

    private void waitForCustomerStatus ( final ChromeDriver customer, final String badge ) {
        waitFor( customer ).until( d -> {
            final WebElement card = customerCard( customer );
            return card != null && card.getText().contains( badge );
        } );
    }

    private static void clickInDriverCard ( final ChromeDriver driver, final String button ) {
        final By card = By.xpath( "//div[contains(@class,'order-card')][.//h3[contains(.,'Order #')]]//button[contains(.,'"
                + button + "')]" );
        waitFor( driver ).until( ExpectedConditions.elementToBeClickable( card ) ).click();
    }

    @Test
    void theCustomerSeesEveryStepWithoutReloading () {
        final ChromeDriver customer = browser();
        logIn( customer, CUSTOMER, "preferences" );
        customer.get( BASE_URL + "orders" );
        waitForCustomerStatus( customer, "Placed" );
        waitFor( customer ).until( ExpectedConditions.textToBePresentInElementLocated(
                By.cssSelector( ".live-status" ), "Live" ) );
        // A full reload would wipe this marker.
        ( (JavascriptExecutor) customer ).executeScript( "window.__stillTheSamePage = true;" );

        final ChromeDriver driver = browser();
        logIn( driver, DRIVER, "driver" );
        waitFor( driver ).until( ExpectedConditions.textToBePresentInElementLocated(
                By.cssSelector( ".orders-section" ), "Order #" + orderId ) );

        clickInDriverCard( driver, "Claim Order" );
        waitForCustomerStatus( customer, "Claimed by driver" );

        clickInDriverCard( driver, "Pick Up Order" );
        waitForCustomerStatus( customer, "On the way" );

        clickInDriverCard( driver, "Mark as Delivered" );
        waitForCustomerStatus( customer, "Delivered" );

        assertEquals( Boolean.TRUE,
                ( (JavascriptExecutor) customer ).executeScript( "return window.__stillTheSamePage === true;" ),
                "the customer's page was never reloaded" );
    }
}
