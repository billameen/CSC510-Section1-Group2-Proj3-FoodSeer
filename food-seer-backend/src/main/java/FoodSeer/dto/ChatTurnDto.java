package FoodSeer.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One earlier message in the conversation, sent back by the chat page so the
 * assistant can follow up ("anything cheaper?").
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatTurnDto {
    /** "user" or "assistant"; anything else is ignored */
    private String role;

    /** The message text */
    private String content;
}
