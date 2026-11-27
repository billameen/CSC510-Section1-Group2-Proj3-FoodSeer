package FoodSeer.dto;

import java.time.LocalDateTime;

import FoodSeer.entity.OrderEvent;

/**
 * One timeline entry as the API sends it. Statuses use the same labels as
 * OrderDto ("Picked Up").
 *
 * @param id
 *            event id
 * @param type
 *            what happened, e.g. CLAIMED
 * @param fromStatus
 *            status before, or null
 * @param toStatus
 *            status after
 * @param actorUsername
 *            who did it, or null for the system
 * @param actorRole
 *            their role, or null
 * @param occurredAt
 *            when
 * @param detail
 *            extra text, or null
 */
public record OrderEventDto(Long id, String type, String fromStatus, String toStatus, String actorUsername,
        String actorRole, LocalDateTime occurredAt, String detail) {

    /**
     * @param event
     *            the stored event
     * @return its API form
     */
    public static OrderEventDto of ( final OrderEvent event ) {
        return new OrderEventDto( event.getId(), event.getType().name(),
                event.getFromStatus() == null ? null : event.getFromStatus().label(), event.getToStatus().label(),
                event.getActorUsername(), event.getActorRole(), event.getOccurredAt(), event.getDetail() );
    }
}
