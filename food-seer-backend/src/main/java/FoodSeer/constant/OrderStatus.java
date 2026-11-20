package FoodSeer.constant;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where an order is in its life. This replaces the free-text status string and
 * the separate isFulfilled flag, which could disagree.
 *
 * <pre>
 * PLACED ──claim──▶ CLAIMED ──pick up──▶ PICKED_UP ──deliver──▶ DELIVERED
 *    │                 │                     │
 *    └────────────── cancel ─────────────────┴──────────────▶ CANCELLED
 * </pre>
 *
 * DELIVERED and CANCELLED are final. Every change goes through
 * {@link #requireTransitionTo(OrderStatus)}, and OrderStateMachine is the only
 * code that calls it.
 */
public enum OrderStatus {

    /** Customer placed it; stock is reserved; waiting for a driver. */
    PLACED("Placed"),

    /** A driver took it. */
    CLAIMED("Claimed"),

    /** The driver collected it from the restaurant. */
    PICKED_UP("Picked Up"),

    /** Delivered. The order is complete and the driver has been paid. */
    DELIVERED("Delivered"),

    /** Cancelled before delivery. Its reserved stock was released. */
    CANCELLED("Cancelled");

    /** The allowed moves. Anything not listed here is a 409. */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            PLACED, EnumSet.of(CLAIMED, CANCELLED),
            CLAIMED, EnumSet.of(PICKED_UP, CANCELLED),
            PICKED_UP, EnumSet.of(DELIVERED, CANCELLED),
            DELIVERED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    private final String label;

    OrderStatus ( final String label ) {
        this.label = label;
    }

    /**
     * @return the human-readable form, which is also how the status was stored
     *         before this enum existed (for example "Picked Up")
     */
    public String label () {
        return label;
    }

    /**
     * @param to
     *            the target status
     * @return true if an order may move from this status to the target
     */
    public boolean canTransitionTo ( final OrderStatus to ) {
        return to != null && TRANSITIONS.get( this ).contains( to );
    }

    /**
     * @param to
     *            the target status
     * @throws IllegalStateException
     *             (409) if the move isn't in the table
     */
    public void requireTransitionTo ( final OrderStatus to ) {
        if ( !canTransitionTo( to ) ) {
            throw new IllegalStateException( "An order that is " + label + " can't become "
                    + ( to == null ? "nothing" : to.label ) + "." );
        }
    }

    /**
     * @return true for DELIVERED and CANCELLED, which allow no further change
     */
    public boolean isFinal () {
        return TRANSITIONS.get( this ).isEmpty();
    }

    /**
     * Parses a status from a request or a stored value. Accepts the enum name
     * ("PICKED_UP") or the old label ("Picked Up"), in any case.
     *
     * @param value
     *            the status
     * @return the status
     * @throws IllegalArgumentException
     *             (400) if it's missing or not a status
     */
    public static OrderStatus parse ( final String value ) {
        if ( value == null || value.isBlank() ) {
            throw new IllegalArgumentException( "A status is required." );
        }
        final String key = value.trim().toUpperCase( Locale.ROOT ).replace( ' ', '_' );
        for ( final OrderStatus status : values() ) {
            if ( status.name().equals( key ) ) {
                return status;
            }
        }
        throw new IllegalArgumentException( "Unknown order status: " + value.trim() );
    }
}
