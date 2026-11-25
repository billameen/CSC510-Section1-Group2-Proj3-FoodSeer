package FoodSeer.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The assistant's answer.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDto {
    /** The assistant's reply text; always present */
    private String message;

    /**
     * Dishes to show as cards, already checked against the menu, stock and
     * the customer's allergies; empty when the assistant is not recommending
     * food
     */
    private List<ChatFoodDto> foods = List.of();

    /**
     * Set when suggestions were left out because they were unsafe or
     * unavailable, so the page can say so; null otherwise
     */
    private String notice;

    /**
     * Creates a text-only answer.
     *
     * @param message
     *            the reply text
     */
    public ChatResponseDto(final String message) {
        this.message = message;
    }
}
