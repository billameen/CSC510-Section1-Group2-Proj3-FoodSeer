package FoodSeer.dto;

import java.util.List;

/**
 * Body of a staff confirmation of a customer report.
 *
 * @param foodIds
 *            the dishes staff confirm were at fault; each must be one of the
 *            report's candidate dishes
 */
public record ConfirmIncidentDto(List<Long> foodIds) {
}
