package FoodSeer.live;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import FoodSeer.entity.Order;
import FoodSeer.repositories.OrderEventRepository;

/**
 * Gives every order a timeline. Orders placed before the event log get one
 * IMPORTED event with their current status. Runs at startup and does nothing
 * once every order has at least one event.
 */
@Component
public class OrderEventBackfill implements ApplicationRunner {

    private static final Logger      LOG = LoggerFactory.getLogger( OrderEventBackfill.class );

    private final OrderEventRepository events;
    private final OrderEventRecorder   recorder;

    /**
     * @param events
     *            timeline repository
     * @param recorder
     *            writes the events
     */
    public OrderEventBackfill ( final OrderEventRepository events, final OrderEventRecorder recorder ) {
        this.events = events;
        this.recorder = recorder;
    }

    @Override
    @Transactional
    public void run ( final ApplicationArguments args ) {
        backfill();
    }

    /**
     * @return how many orders were given an IMPORTED event
     */
    @Transactional
    public int backfill () {
        final List<Order> missing = events.findOrdersWithoutEvents();
        missing.forEach( recorder::imported );
        if ( !missing.isEmpty() ) {
            LOG.info( "Started the timeline of {} existing orders.", missing.size() );
        }
        return missing.size();
    }
}
