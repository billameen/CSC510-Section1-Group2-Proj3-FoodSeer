package FoodSeer.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring Boot's "open entity manager in view", for every endpoint except the
 * live stream.
 *
 * It keeps a request's database session (and its JDBC connection) open until
 * the request finishes. A live stream doesn't finish for up to 30 minutes, so
 * with it on, every open browser tab held one of the pool's 10 connections and
 * the 10th tab froze the whole app. The stream only does short repository
 * calls, so it doesn't need it. Declaring the interceptor bean makes Spring
 * Boot's own one step aside; everything else behaves exactly as before.
 */
@Configuration
public class OpenEntityManagerInViewConfig {

    /**
     * @return the interceptor (Spring Boot backs off when this bean exists)
     */
    @Bean
    public OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor () {
        return new OpenEntityManagerInViewInterceptor();
    }

    /**
     * Registers the interceptor for everything but /api/stream. Separate from
     * the bean so web-only test slices, which have no database, can load it.
     */
    @Configuration
    public static class Registration implements WebMvcConfigurer {

        private final ObjectProvider<OpenEntityManagerInViewInterceptor> interceptor;

        /**
         * @param interceptor
         *            the interceptor, if there is a database
         */
        public Registration ( final ObjectProvider<OpenEntityManagerInViewInterceptor> interceptor ) {
            this.interceptor = interceptor;
        }

        @Override
        public void addInterceptors ( final InterceptorRegistry registry ) {
            interceptor.ifAvailable(
                    i -> registry.addWebRequestInterceptor( i ).excludePathPatterns( "/api/stream" ) );
        }
    }
}
