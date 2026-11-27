package FoodSeer.chat;

import java.util.List;
import java.util.Locale;

import FoodSeer.entity.Food;
import FoodSeer.entity.User;

/**
 * Writes the system message that grounds the model in the real menu and the
 * customer's own profile.
 *
 * The model sees each dish's id, so it can answer with ids instead of names,
 * and the response can show cards for exactly the dishes it chose.
 */
public final class ChatPromptBuilder {

    private ChatPromptBuilder() {
        // utility class
    }

    /**
     * Writes the system message.
     *
     * @param menu
     *            the dishes the model may suggest
     * @param user
     *            the customer asking, may be null
     * @param menuIsPrefiltered
     *            true if dishes with the customer's allergens were already
     *            removed from {@code menu}
     * @return the system message text
     */
    public static String systemPrompt(final List<Food> menu, final User user, final boolean menuIsPrefiltered) {
        final StringBuilder prompt = new StringBuilder();
        prompt.append("You are FoodSeer's food assistant. You help a customer choose dishes from the menu below.\n")
                .append("Answer with only a JSON object with two keys:\n")
                .append("- \"reply\": a short, friendly answer in plain text, with no emojis and no marketing.\n")
                .append("- \"foodIds\": the ids of up to 3 menu dishes you recommend, best first. ")
                .append("Use [] when you are not recommending food, for example for a greeting.\n")
                .append("Only use ids from the menu. Never invent a dish, an id, or a price. ")
                .append("If nothing on the menu fits, say so and use [].\n")
                .append("Never recommend a dish that contains anything on the customer's allergy list.\n\n");

        final String restrictions = clean(user == null ? null : user.getDietaryRestrictions());
        prompt.append("Customer allergies and dietary restrictions: ")
                .append(restrictions.isEmpty() ? "none saved" : restrictions).append('\n');
        if (!restrictions.isEmpty() && menuIsPrefiltered) {
            prompt.append("(Dishes containing these were already removed from the menu below.)\n");
        }
        final Integer maxPrice = maxPrice(user == null ? null : user.getCostPreference());
        prompt.append("Customer budget: ")
                .append(maxPrice == null ? "no limit" : "up to $" + maxPrice + " per dish").append("\n\n");

        if (menu == null || menu.isEmpty()) {
            prompt.append("Menu: nothing is available for this customer right now. ")
                    .append("Tell them so, and use [] for foodIds.\n");
            return prompt.toString();
        }
        prompt.append("Menu (id | name | price | calories | contains):\n");
        for (final Food food : menu) {
            prompt.append(food.getId()).append(" | ").append(clean(food.getFoodName())).append(" | $")
                    .append(food.getPrice()).append(" | ")
                    .append(food.getCalories() == null ? "unknown" : food.getCalories() + " kcal").append(" | ")
                    .append(tags(food)).append('\n');
        }
        return prompt.toString();
    }

    /**
     * Turns a saved budget choice into a per-dish price limit, the same way the
     * Recommendations page does.
     *
     * @param costPreference
     *            "budget", "moderate", "premium", "no-limit" or null
     * @return the most a dish may cost, or null for no limit
     */
    public static Integer maxPrice(final String costPreference) {
        if (costPreference == null) {
            return null;
        }
        switch (costPreference.trim().toLowerCase(Locale.ROOT)) {
            case "budget":
                return 10;
            case "moderate":
                return 20;
            case "premium":
                return 35;
            default:
                return null;
        }
    }

    private static String tags(final Food food) {
        if (food.getAllergies() == null || food.getAllergies().isEmpty()) {
            return "no common allergens";
        }
        return clean(String.join(", ", food.getAllergies()));
    }

    /** Keeps each value on one line so it cannot break the menu table, and caps its length. */
    private static String clean(final String value) {
        if (value == null) {
            return "";
        }
        final String oneLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) : oneLine;
    }
}
