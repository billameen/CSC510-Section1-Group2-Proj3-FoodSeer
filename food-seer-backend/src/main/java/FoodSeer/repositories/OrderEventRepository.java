package FoodSeer.repositories;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import FoodSeer.entity.Order;
import FoodSeer.entity.OrderEvent;

/** Order timelines. */
public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {

    /**
     * @param orderId
     *            the order
     * @return its events, oldest first
     */
    List<OrderEvent> findByOrderIdOrderByIdAsc ( Long orderId );

    /**
     * @return orders placed before the event log existed, which have no
     *         events yet
     */
    @Query ( "SELECT o FROM Order o WHERE NOT EXISTS (SELECT e FROM OrderEvent e WHERE e.orderId = o.id)" )
    List<Order> findOrdersWithoutEvents ();
}
