package FoodSeer.constant;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores OrderStatus in the existing "status" column. New rows get the enum
 * name ("PICKED_UP"). Rows written before Milestone 3 hold the old labels
 * ("Placed", "Picked Up", ...) and still read correctly, so no data migration
 * is needed. A row with no status is resolved by Order's @PostLoad.
 */
@Converter
public class OrderStatusConverter implements AttributeConverter<OrderStatus, String> {

    @Override
    public String convertToDatabaseColumn ( final OrderStatus status ) {
        return status == null ? null : status.name();
    }

    @Override
    public OrderStatus convertToEntityAttribute ( final String stored ) {
        if ( stored == null || stored.isBlank() ) {
            return null;
        }
        return OrderStatus.parse( stored );
    }
}
