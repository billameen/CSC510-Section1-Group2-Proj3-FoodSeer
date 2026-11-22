package FoodSeer.repositories;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import FoodSeer.entity.OutboxEvent;

/** The live-update outbox. */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * @return the next messages to send, oldest first
     */
    List<OutboxEvent> findTop200ByPublishedAtIsNullOrderByIdAsc ();

    /**
     * Messages already sent after a given id, for a client resuming its
     * stream.
     *
     * @param afterId
     *            exclusive lower bound
     * @return up to 500 sent messages, oldest first
     */
    List<OutboxEvent> findTop500ByIdGreaterThanAndPublishedAtIsNotNullOrderByIdAsc ( Long afterId );

    /**
     * @param orderId
     *            an order
     * @return its messages, oldest first
     */
    List<OutboxEvent> findByOrderIdOrderByIdAsc ( Long orderId );

    /**
     * Drops sent messages older than the resume window.
     *
     * @param cutoff
     *            messages sent before this go
     * @return how many were deleted
     */
    @Modifying
    @Transactional
    @Query ( "DELETE FROM OutboxEvent e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :cutoff" )
    int deletePublishedBefore ( @Param ( "cutoff" ) LocalDateTime cutoff );

    /**
     * Stamps a message as sent. An update rather than a save, so a row deleted
     * meanwhile is simply skipped.
     *
     * @param id
     *            the message
     * @param at
     *            when it was sent
     * @return 1, or 0 if the row is gone
     */
    @Modifying
    @Transactional
    @Query ( "UPDATE OutboxEvent e SET e.publishedAt = :at WHERE e.id = :id AND e.publishedAt IS NULL" )
    int markPublished ( @Param ( "id" ) Long id, @Param ( "at" ) LocalDateTime at );
}
