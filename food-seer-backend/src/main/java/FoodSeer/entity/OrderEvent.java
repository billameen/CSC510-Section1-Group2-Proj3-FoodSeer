package FoodSeer.entity;

import java.time.LocalDateTime;

import FoodSeer.constant.OrderEventType;
import FoodSeer.constant.OrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One entry in an order's timeline. Rows are only ever inserted, never
 * updated or deleted, and each one is written in the same transaction as the
 * change it records, so the timeline can't disagree with the order.
 */
@Entity
@Table ( name = "order_events", indexes = @Index ( name = "idx_order_events_order", columnList = "order_id" ) )
public class OrderEvent {

    @Id
    @GeneratedValue ( strategy = GenerationType.IDENTITY )
    private Long          id;

    @Column ( name = "order_id", nullable = false, updatable = false )
    private Long          orderId;

    @Enumerated ( EnumType.STRING )
    @Column ( nullable = false, updatable = false, length = 32 )
    private OrderEventType type;

    @Enumerated ( EnumType.STRING )
    @Column ( name = "from_status", updatable = false, length = 32 )
    private OrderStatus   fromStatus;

    @Enumerated ( EnumType.STRING )
    @Column ( name = "to_status", nullable = false, updatable = false, length = 32 )
    private OrderStatus   toStatus;

    @Column ( name = "actor_username", updatable = false )
    private String        actorUsername;

    @Column ( name = "actor_role", updatable = false, length = 32 )
    private String        actorRole;

    @Column ( name = "occurred_at", nullable = false, updatable = false )
    private LocalDateTime occurredAt;

    @Column ( updatable = false, length = 500 )
    private String        detail;

    /** For JPA. */
    protected OrderEvent () {
    }

    /**
     * @param orderId
     *            the order
     * @param type
     *            what happened
     * @param fromStatus
     *            the status before, or null if there was none
     * @param toStatus
     *            the status after
     * @param actorUsername
     *            who did it, or null for the system
     * @param actorRole
     *            their role without the ROLE_ prefix, or null
     * @param detail
     *            extra text, or null
     */
    public OrderEvent ( final Long orderId, final OrderEventType type, final OrderStatus fromStatus,
            final OrderStatus toStatus, final String actorUsername, final String actorRole, final String detail ) {
        this.orderId = orderId;
        this.type = type;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorUsername = actorUsername;
        this.actorRole = actorRole;
        this.detail = detail;
        this.occurredAt = LocalDateTime.now();
    }

    public Long getId () {
        return id;
    }

    public Long getOrderId () {
        return orderId;
    }

    public OrderEventType getType () {
        return type;
    }

    public OrderStatus getFromStatus () {
        return fromStatus;
    }

    public OrderStatus getToStatus () {
        return toStatus;
    }

    public String getActorUsername () {
        return actorUsername;
    }

    public String getActorRole () {
        return actorRole;
    }

    public LocalDateTime getOccurredAt () {
        return occurredAt;
    }

    public String getDetail () {
        return detail;
    }
}
