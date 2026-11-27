package FoodSeer.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO for chat request to Ollama.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDto {
    /** The message to send to the AI */
    private String message;

    /**
     * Earlier messages in this conversation, oldest first. Optional; only the
     * most recent turns are used (foodseer.chat.history-turns).
     */
    private List<ChatTurnDto> history;

    /**
     * Creates a request without history.
     *
     * @param message
     *            the message to send
     */
    public ChatRequestDto(final String message) {
        this.message = message;
    }
}

