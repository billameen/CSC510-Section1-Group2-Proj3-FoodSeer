package FoodSeer.constant;

/**
 * Where an allergy incident is in its review.
 *
 * Restaurant reports start ACTIVE and alert customers straight away. Customer
 * reports start PENDING_REVIEW and alert nobody until staff confirm which dish
 * was at fault (spiral review option (a) in docs/allergy-trace-back.md).
 */
public enum IncidentStatus {
    /** Reported by a customer; nobody has been alerted yet. */
    PENDING_REVIEW,
    /** Traced; affected customers have been alerted. */
    ACTIVE,
    /** Staff reviewed a customer report and found nothing to trace. */
    DISMISSED
}
