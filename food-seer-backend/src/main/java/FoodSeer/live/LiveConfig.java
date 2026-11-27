package FoodSeer.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduled jobs behind live updates (outbox dispatch, purge,
 * stream heartbeats). On unless app.live.scheduling-enabled=false. The tests
 * turn it off (src/test/resources/application-default.properties) because
 * every cached test context shares one database and outlives its test class,
 * so a live dispatcher would steal other tests' messages; tests run the jobs
 * on their own thread instead.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty ( name = "app.live.scheduling-enabled", havingValue = "true", matchIfMissing = true )
public class LiveConfig {
}
