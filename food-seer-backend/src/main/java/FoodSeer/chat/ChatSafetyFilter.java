package FoodSeer.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import FoodSeer.entity.Food;
import FoodSeer.service.AllergenMatcher;

/**
 * The last check before a dish the model suggested reaches the customer.
 *
 * The model's suggestions are never trusted. A suggested id is kept only if it
 * is an active dish on the menu, it is in stock, and none of its allergen tags
 * touch the customer's saved restrictions according to
 * {@link AllergenMatcher}, the same matcher allergy trace-back uses. This is
 * what makes the assistant allergy-safe by construction: whatever the model
 * says, an unsafe or made-up dish cannot come back as a card.
 */
public final class ChatSafetyFilter {

    /** Most dish cards shown for one answer. */
    public static final int MAX_SUGGESTIONS = 3;

    /** Why a suggested id was removed. */
    public enum DropReason {
        /** No active dish has this id, so the model made it up or it was removed. */
        NOT_ON_MENU,
        /** The dish exists but none are in stock. */
        OUT_OF_STOCK,
        /** The dish carries an allergen on the customer's list. */
        ALLERGEN
    }

    /**
     * The outcome of filtering one answer.
     *
     * @param safeFoods
     *            the dishes that may be shown, in the model's order, at most
     *            {@link #MAX_SUGGESTIONS}
     * @param dropped
     *            how many suggestions were removed, by reason
     */
    public record Result(List<Food> safeFoods, Map<DropReason, Integer> dropped) {

        /**
         * @param reason
         *            a reason
         * @return how many suggestions were removed for it
         */
        public int droppedFor(final DropReason reason) {
            return dropped.getOrDefault(reason, 0);
        }

        /**
         * @return how many suggestions were removed in total
         */
        public int droppedCount() {
            return dropped.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private ChatSafetyFilter() {
        // utility class
    }

    /**
     * Keeps only the suggestions that are safe to show this customer.
     *
     * @param proposedIds
     *            the dish ids the model suggested, may be null
     * @param menu
     *            every active dish, including ones that are out of stock
     * @param restrictions
     *            the customer's saved dietary restrictions, may be null
     * @return the dishes to show and what was removed
     */
    public static Result filter(final List<Long> proposedIds, final Collection<Food> menu,
            final String restrictions) {
        final Map<DropReason, Integer> dropped = new EnumMap<>(DropReason.class);
        if (proposedIds == null || proposedIds.isEmpty()) {
            return new Result(List.of(), Collections.unmodifiableMap(dropped));
        }

        final Map<Long, Food> byId = new HashMap<>();
        if (menu != null) {
            for (final Food food : menu) {
                if (food != null && food.getId() != null && food.isActive()) {
                    byId.put(food.getId(), food);
                }
            }
        }

        final List<Food> safe = new ArrayList<>();
        for (final Long id : new LinkedHashSet<>(proposedIds)) {
            final Food food = id == null ? null : byId.get(id);
            final DropReason reason;
            if (food == null) {
                reason = DropReason.NOT_ON_MENU;
            } else if (!isInStock(food)) {
                reason = DropReason.OUT_OF_STOCK;
            } else if (!isSafeFor(food, restrictions)) {
                reason = DropReason.ALLERGEN;
            } else {
                reason = null;
            }
            if (reason != null) {
                dropped.merge(reason, 1, Integer::sum);
            } else if (safe.size() < MAX_SUGGESTIONS) {
                safe.add(food);
            }
        }
        return new Result(List.copyOf(safe), Collections.unmodifiableMap(dropped));
    }

    /**
     * @param food
     *            a dish
     * @return true if it is active and at least one is in stock
     */
    public static boolean isInStock(final Food food) {
        return food != null && food.isActive() && food.getAmount() > 0;
    }

    /**
     * Checks a dish's allergen tags against a customer's restrictions.
     *
     * @param food
     *            a dish
     * @param restrictions
     *            the customer's saved restrictions, such as "PEANUTS, no dairy
     *            products"; may be null
     * @return true if no tag on the dish touches any restriction
     */
    public static boolean isSafeFor(final Food food, final String restrictions) {
        if (restrictions == null || restrictions.isBlank() || food.getAllergies() == null) {
            return true;
        }
        for (final String tag : food.getAllergies()) {
            if (tag != null && !tag.isBlank() && AllergenMatcher.restrictionsAffectedBy(restrictions, tag)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Explains what was removed, in words a customer understands.
     *
     * @param result
     *            the filter outcome
     * @return a sentence or two, or null if nothing was removed
     */
    public static String noticeFor(final Result result) {
        final List<String> parts = new ArrayList<>();
        final int allergen = result.droppedFor(DropReason.ALLERGEN);
        if (allergen > 0) {
            parts.add("I left out " + count(allergen) + " that "
                    + (allergen == 1 ? "contains" : "contain") + " something on your allergy list.");
        }
        final int unavailable = result.droppedFor(DropReason.NOT_ON_MENU) + result.droppedFor(DropReason.OUT_OF_STOCK);
        if (unavailable > 0) {
            parts.add("I left out " + count(unavailable) + " that "
                    + (unavailable == 1 ? "isn't" : "aren't") + " available on the menu right now.");
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private static String count(final int n) {
        return n == 1 ? "1 suggestion" : n + " suggestions";
    }
}
