package FoodSeer.constant;

import static FoodSeer.constant.OrderStatus.CANCELLED;
import static FoodSeer.constant.OrderStatus.CLAIMED;
import static FoodSeer.constant.OrderStatus.DELIVERED;
import static FoodSeer.constant.OrderStatus.PICKED_UP;
import static FoodSeer.constant.OrderStatus.PLACED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {

    /** The table from the issue, written out by hand so the enum is checked against it. */
    private static final Set<String> ALLOWED = Set.of(
            "PLACED>CLAIMED", "PLACED>CANCELLED",
            "CLAIMED>PICKED_UP", "CLAIMED>CANCELLED",
            "PICKED_UP>DELIVERED", "PICKED_UP>CANCELLED");

    static Stream<Arguments> everyPair() {
        return Stream.of(OrderStatus.values())
                .flatMap(from -> Stream.of(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void transitionTableMatchesTheIssue(final OrderStatus from, final OrderStatus to) {
        final boolean allowed = ALLOWED.contains(from + ">" + to);

        assertEquals(allowed, from.canTransitionTo(to));
        if (allowed) {
            from.requireTransitionTo(to);
        } else {
            assertThrows(IllegalStateException.class, () -> from.requireTransitionTo(to));
        }
    }

    @Test
    void deliveredAndCancelledAreFinal() {
        assertTrue(DELIVERED.isFinal());
        assertTrue(CANCELLED.isFinal());
        assertFalse(PLACED.isFinal());
        assertFalse(PLACED.canTransitionTo(null));
    }

    @Test
    void parsesNamesAndLegacyLabels() {
        assertEquals(PICKED_UP, OrderStatus.parse("Picked Up"));
        assertEquals(PICKED_UP, OrderStatus.parse("PICKED_UP"));
        assertEquals(PLACED, OrderStatus.parse("placed"));
        assertEquals(CLAIMED, OrderStatus.parse(" Claimed "));
        assertEquals(DELIVERED, OrderStatus.parse("Delivered"));
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.parse(null));
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.parse(""));
        assertThrows(IllegalArgumentException.class, () -> OrderStatus.parse("Teleported"));
    }

    @Test
    void converterReadsLegacyRowsAndWritesEnumNames() {
        final OrderStatusConverter converter = new OrderStatusConverter();

        assertEquals(PLACED, converter.convertToEntityAttribute("Placed"));
        assertEquals(PICKED_UP, converter.convertToEntityAttribute("Picked Up"));
        assertEquals(DELIVERED, converter.convertToEntityAttribute("DELIVERED"));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToEntityAttribute(""));
        assertEquals("PICKED_UP", converter.convertToDatabaseColumn(PICKED_UP));
        assertNull(converter.convertToDatabaseColumn(null));
    }
}
