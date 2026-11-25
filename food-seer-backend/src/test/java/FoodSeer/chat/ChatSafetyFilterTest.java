package FoodSeer.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import FoodSeer.chat.ChatSafetyFilter.DropReason;
import FoodSeer.chat.ChatSafetyFilter.Result;
import FoodSeer.entity.Food;

class ChatSafetyFilterTest {

    static Food food(final long id, final String name, final int stock, final String... tags) {
        final Food food = new Food(name, stock, 10, new ArrayList<>(Arrays.asList(tags)));
        food.setId(id);
        return food;
    }

    private static final Food SALAD = food(1, "GARDEN SALAD", 5);
    private static final Food COOKIES = food(2, "COOKIES", 5, "GLUTEN", "WHEAT", "MILK", "DAIRY", "EGGS", "SOY",
            "TREE-NUTS", "PEANUTS");
    private static final Food YOGURT = food(3, "YOGURT", 5, "MILK", "DAIRY", "LACTOSE");
    private static final Food LOBSTER = food(4, "LOBSTER", 5, "SHELLFISH");
    private static final Food SALMON = food(5, "SALMON", 0, "FISH");
    private static final Food APPLE = food(6, "APPLE", 5);
    private static final Food TEA = food(7, "TEA", 5, "CAFFEINE");
    private static final Food BANANA = food(8, "BANANA", 5);

    private static final List<Food> MENU = List.of(SALAD, COOKIES, YOGURT, LOBSTER, SALMON, APPLE, TEA, BANANA);

    private static List<Long> ids(final List<Food> foods) {
        return foods.stream().map(Food::getId).toList();
    }

    @Test
    void keepsSafeInStockDishesInTheModelsOrder() {
        final Result result = ChatSafetyFilter.filter(List.of(6L, 1L), MENU, "PEANUTS");

        assertEquals(List.of(6L, 1L), ids(result.safeFoods()));
        assertEquals(0, result.droppedCount());
        assertNull(ChatSafetyFilter.noticeFor(result));
    }

    @Test
    void dropsIdsThatAreNotOnTheMenu() {
        final Result result = ChatSafetyFilter.filter(List.of(999L, 1L, 0L), MENU, null);

        assertEquals(List.of(1L), ids(result.safeFoods()));
        assertEquals(2, result.droppedFor(DropReason.NOT_ON_MENU));
    }

    @Test
    void dropsRemovedDishes() {
        final Food removed = food(9, "OLD SPECIAL", 5);
        removed.setActive(false);
        final List<Food> menu = new ArrayList<>(MENU);
        menu.add(removed);

        final Result result = ChatSafetyFilter.filter(List.of(9L), menu, null);

        assertTrue(result.safeFoods().isEmpty());
        assertEquals(1, result.droppedFor(DropReason.NOT_ON_MENU));
    }

    @Test
    void dropsDishesThatAreOutOfStock() {
        final Result result = ChatSafetyFilter.filter(List.of(5L), MENU, null);

        assertTrue(result.safeFoods().isEmpty());
        assertEquals(1, result.droppedFor(DropReason.OUT_OF_STOCK));
    }

    @Test
    void dropsDishesWithASavedAllergen() {
        final Result result = ChatSafetyFilter.filter(List.of(2L, 1L), MENU, "PEANUTS");

        assertEquals(List.of(1L), ids(result.safeFoods()));
        assertEquals(1, result.droppedFor(DropReason.ALLERGEN));
        assertEquals(List.of(2L), ids(result.blockedForAllergen()));
        assertEquals("I left out 1 suggestion that contains something on your allergy list.",
                ChatSafetyFilter.noticeFor(result));
    }

    /** The phrasings people type, matched the same way allergy trace-back matches them. */
    @ParameterizedTest
    @CsvSource({ "peanut allergy, 2", "allergic to peanuts, 2", "no dairy products, 3", "lactose intolerant, 3",
        "nuts, 2", "tree nuts, 2", "egg whites, 2", "shellfish, 4", "gluten, 2", "MILK, 3", "Peanuts, 2" })
    void freeTextRestrictionsBlockTheMatchingDish(final String restriction, final long blockedId) {
        final Result result = ChatSafetyFilter.filter(List.of(blockedId), MENU, restriction);

        assertTrue(result.safeFoods().isEmpty(), restriction + " should block dish " + blockedId);
        assertEquals(1, result.droppedFor(DropReason.ALLERGEN));
    }

    @Test
    void fishDoesNotBlockShellfishAndShellfishDoesNotBlockFish() {
        assertTrue(ChatSafetyFilter.isSafeFor(LOBSTER, "fish"));
        assertTrue(ChatSafetyFilter.isSafeFor(food(10, "TUNA", 5, "FISH"), "shellfish"));
    }

    @Test
    void severalRestrictionsAreAllChecked() {
        final Result result = ChatSafetyFilter.filter(List.of(2L, 3L, 4L, 6L), MENU, "shellfish, no dairy products");

        assertEquals(List.of(6L), ids(result.safeFoods()));
        assertEquals(3, result.droppedFor(DropReason.ALLERGEN));
    }

    @Test
    void noRestrictionsMeansEveryInStockDishIsSafe() {
        assertEquals(List.of(2L, 3L), ids(ChatSafetyFilter.filter(List.of(2L, 3L), MENU, null).safeFoods()));
        assertEquals(List.of(2L, 3L), ids(ChatSafetyFilter.filter(List.of(2L, 3L), MENU, "  ").safeFoods()));
    }

    @Test
    void aDishWithNoTagsIsSafe() {
        final Food untagged = food(11, "MYSTERY", 5);
        untagged.setAllergies(null);

        assertTrue(ChatSafetyFilter.isSafeFor(untagged, "PEANUTS"));
    }

    @Test
    void duplicatesCountOnce() {
        final Result result = ChatSafetyFilter.filter(List.of(1L, 1L, 2L, 2L), MENU, "PEANUTS");

        assertEquals(List.of(1L), ids(result.safeFoods()));
        assertEquals(1, result.droppedFor(DropReason.ALLERGEN));
    }

    @Test
    void showsAtMostThreeDishes() {
        final Result result = ChatSafetyFilter.filter(List.of(1L, 6L, 7L, 8L), MENU, null);

        assertEquals(ChatSafetyFilter.MAX_SUGGESTIONS, result.safeFoods().size());
        assertEquals(List.of(1L, 6L, 7L), ids(result.safeFoods()));
        assertEquals(0, result.droppedCount(), "extra safe dishes are not unsafe, so no notice");
    }

    @Test
    void unsafeDishesNeverTakeASlotFromSafeOnes() {
        final Result result = ChatSafetyFilter.filter(List.of(2L, 3L, 1L, 6L, 7L), MENU, "PEANUTS, MILK");

        assertEquals(List.of(1L, 6L, 7L), ids(result.safeFoods()));
    }

    @Test
    void nothingProposedMeansNothingShown() {
        assertTrue(ChatSafetyFilter.filter(null, MENU, "PEANUTS").safeFoods().isEmpty());
        assertTrue(ChatSafetyFilter.filter(List.of(), MENU, "PEANUTS").safeFoods().isEmpty());
        assertTrue(ChatSafetyFilter.filter(List.of(1L), null, null).safeFoods().isEmpty());
    }

    @Test
    void theNoticeCountsEveryReason() {
        final Result result = ChatSafetyFilter.filter(List.of(2L, 3L, 5L, 999L, 1L), MENU, "PEANUTS, MILK");

        assertEquals("I left out 2 suggestions that contain something on your allergy list. "
                + "I left out 2 suggestions that aren't available on the menu right now.",
                ChatSafetyFilter.noticeFor(result));
    }

    @Test
    void inStockMeansActiveWithAtLeastOneLeft() {
        assertTrue(ChatSafetyFilter.isInStock(SALAD));
        assertFalse(ChatSafetyFilter.isInStock(SALMON));
        assertFalse(ChatSafetyFilter.isInStock(null));
    }
}
