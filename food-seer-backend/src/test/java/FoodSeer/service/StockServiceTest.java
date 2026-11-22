package FoodSeer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import FoodSeer.entity.Food;
import FoodSeer.entity.Order;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.repositories.FoodRepository;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

    @Mock
    private FoodRepository foods;
    @InjectMocks
    private StockService stock;

    private static Food food(long id, String name, int amount) {
        final Food f = new Food(name, amount, 5, new ArrayList<>());
        f.setId(id);
        return f;
    }

    @Test
    void reservesEachLineByItsQuantity() {
        final Food coffee = food(1L, "COFFEE", 5);
        final Food tea = food(2L, "TEA", 3);
        when(foods.findByIdForUpdate(1L)).thenReturn(Optional.of(coffee));
        when(foods.findByIdForUpdate(2L)).thenReturn(Optional.of(tea));

        final List<Food> units = stock.reserve(List.of(1L, 2L, 1L));

        assertEquals(3, coffee.getAmount());
        assertEquals(2, tea.getAmount());
        assertEquals(List.of(coffee, tea, coffee), units);
    }

    @Test
    void checksEveryLineBeforeChangingAny() {
        final Food coffee = food(1L, "COFFEE", 5);
        final Food tea = food(2L, "TEA", 0);
        when(foods.findByIdForUpdate(1L)).thenReturn(Optional.of(coffee));
        when(foods.findByIdForUpdate(2L)).thenReturn(Optional.of(tea));

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> stock.reserve(List.of(1L, 2L)));

        assertTrue(e.getMessage().contains("TEA"));
        assertEquals(5, coffee.getAmount(), "coffee must not be decremented when tea is short");
        verify(foods, never()).save(any());
    }

    @Test
    void exactlyTheLastUnitIsAllowedButOneMoreIsNot() {
        when(foods.findByIdForUpdate(1L)).thenReturn(Optional.of(food(1L, "COFFEE", 2)));
        stock.reserve(List.of(1L, 1L));

        when(foods.findByIdForUpdate(2L)).thenReturn(Optional.of(food(2L, "TEA", 2)));
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(List.of(2L, 2L, 2L)));
    }

    @Test
    void rejectsEmptyOrdersMissingIdsAndUnknownFoods() {
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(List.of()));
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(null));
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(Arrays.asList(1L, null)));
        when(foods.findByIdForUpdate(9L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> stock.reserve(List.of(9L)));
    }

    @Test
    void releaseGivesEveryUnitBack() {
        final Food coffee = food(1L, "COFFEE", 0);
        final Food tea = food(2L, "TEA", 1);
        when(foods.findByIdForUpdate(1L)).thenReturn(Optional.of(coffee));
        when(foods.findByIdForUpdate(2L)).thenReturn(Optional.of(tea));
        final Order order = new Order(7L, "Lunch");
        order.setFoods(new ArrayList<>(List.of(coffee, coffee, tea)));

        stock.release(order);

        assertEquals(2, coffee.getAmount());
        assertEquals(2, tea.getAmount());
    }
}
