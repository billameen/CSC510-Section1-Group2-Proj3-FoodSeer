package FoodSeer.live;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import FoodSeer.entity.OutboxEvent;
import FoodSeer.repositories.OutboxEventRepository;

/**
 * Queues a live-update message. MANDATORY: the message is saved in the
 * caller's transaction, so it exists if and only if the change does.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository outbox;
    private final ObjectMapper          json;

    /**
     * @param outbox
     *            outbox repository
     * @param json
     *            Spring's JSON mapper (writes dates as ISO strings)
     */
    public OutboxWriter ( final OutboxEventRepository outbox, final ObjectMapper json ) {
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * @param type
     *            message type
     * @param payload
     *            message body, serialised as JSON
     * @param orderId
     *            the order it concerns, or null
     * @param users
     *            usernames who receive it (nulls are skipped)
     * @param roles
     *            roles whose members receive it
     * @return the queued message
     */
    @Transactional ( propagation = Propagation.MANDATORY )
    public OutboxEvent write ( final String type, final Map<String, ?> payload, final Long orderId,
            final Collection<String> users, final Collection<String> roles ) {
        final Set<String> recipients = new LinkedHashSet<>();
        users.stream().filter( Objects::nonNull ).forEach( recipients::add );
        try {
            return outbox.save( new OutboxEvent( type, json.writeValueAsString( payload ), orderId, recipients,
                    roles ) );
        }
        catch ( final JsonProcessingException e ) {
            throw new IllegalStateException( "Couldn't serialise the " + type + " message.", e );
        }
    }
}
