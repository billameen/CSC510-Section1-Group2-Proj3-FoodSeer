package FoodSeer.constant;

/**
 * What happened to an order, as recorded in its timeline. The status changes
 * share their names with {@link OrderStatus} so a transition maps straight to
 * its event.
 */
public enum OrderEventType {
    /** The customer placed the order. */
    PLACED,
    /** A driver claimed it. */
    CLAIMED,
    /** The driver picked it up. */
    PICKED_UP,
    /** The driver delivered it. */
    DELIVERED,
    /** The customer, staff or an admin cancelled it. */
    CANCELLED,
    /** Staff or the driver acknowledged the allergy warning. */
    ALLERGY_ACKNOWLEDGED,
    /** The order existed before the event log, so its history starts here. */
    IMPORTED;

    /**
     * @param status
     *            the status an order just moved to
     * @return the event for that move
     */
    public static OrderEventType forStatus ( final OrderStatus status ) {
        return valueOf( status.name() );
    }
}
