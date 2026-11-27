package FoodSeer.entity;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A message waiting to go out on the live stream. It is written in the same
 * transaction as the change it describes (the transactional outbox), so a
 * change that rolls back never produces a message, and a crash after commit
 * only delays one. The dispatcher sends rows in id order and stamps
 * {@code publishedAt}; the id doubles as the SSE event id clients resume from.
 */
@Entity
@Table ( name = "outbox_events", indexes = { @Index ( name = "idx_outbox_published", columnList = "published_at" ),
        @Index ( name = "idx_outbox_order", columnList = "order_id" ) } )
public class OutboxEvent {

    @Id
    @GeneratedValue ( strategy = GenerationType.IDENTITY )
    private Long          id;

    /** For example order.event, safety.notice, incident.reported. */
    @Column ( nullable = false, length = 64 )
    private String        type;

    /** The message body as JSON. */
    @Column ( nullable = false, length = 4000 )
    private String        payload;

    /** The order it is about, if any. */
    @Column ( name = "order_id" )
    private Long          orderId;

    /** Comma-separated usernames who receive it. */
    @Column ( name = "target_users", length = 500 )
    private String        targetUsers;

    /** Comma-separated roles (no ROLE_ prefix) whose members all receive it. */
    @Column ( name = "target_roles", length = 100 )
    private String        targetRoles;

    @Column ( name = "created_at", nullable = false )
    private LocalDateTime createdAt;

    @Column ( name = "published_at" )
    private LocalDateTime publishedAt;

    /** For JPA. */
    protected OutboxEvent () {
    }

    /**
     * @param type
     *            message type
     * @param payload
     *            JSON body
     * @param orderId
     *            the order it concerns, or null
     * @param users
     *            usernames who receive it
     * @param roles
     *            roles (no ROLE_ prefix) whose members receive it
     */
    public OutboxEvent ( final String type, final String payload, final Long orderId, final Collection<String> users,
            final Collection<String> roles ) {
        this.type = type;
        this.payload = payload;
        this.orderId = orderId;
        this.targetUsers = String.join( ",", users );
        this.targetRoles = String.join( ",", roles );
        this.createdAt = LocalDateTime.now();
    }

    /**
     * @param username
     *            a signed-in user
     * @param role
     *            their role without the ROLE_ prefix
     * @return whether this message is for them
     */
    public boolean isFor ( final String username, final String role ) {
        return split( targetUsers ).contains( username ) || split( targetRoles ).contains( role );
    }

    private static List<String> split ( final String csv ) {
        return csv == null || csv.isEmpty() ? List.of() : Arrays.asList( csv.split( "," ) );
    }

    public Long getId () {
        return id;
    }

    public String getType () {
        return type;
    }

    public String getPayload () {
        return payload;
    }

    public Long getOrderId () {
        return orderId;
    }

    public List<String> getTargetUsers () {
        return split( targetUsers );
    }

    public List<String> getTargetRoles () {
        return split( targetRoles );
    }

    public LocalDateTime getCreatedAt () {
        return createdAt;
    }

    public LocalDateTime getPublishedAt () {
        return publishedAt;
    }

    public void setPublishedAt ( final LocalDateTime publishedAt ) {
        this.publishedAt = publishedAt;
    }
}
