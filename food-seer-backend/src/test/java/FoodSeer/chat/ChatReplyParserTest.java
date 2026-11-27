package FoodSeer.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import FoodSeer.chat.ChatReplyParser.ParsedReply;

class ChatReplyParserTest {

    @Test
    void readsTheAskedForShape() {
        final ParsedReply parsed = ChatReplyParser.parse("{\"reply\": \"Try these.\", \"foodIds\": [3, 7]}");

        assertTrue(parsed.structured());
        assertEquals("Try these.", parsed.reply());
        assertEquals(List.of(3L, 7L), parsed.foodIds());
    }

    @Test
    void readsJsonInsideACodeFence() {
        final ParsedReply parsed = ChatReplyParser
                .parse("Sure!\n```json\n{\"reply\": \"Soup it is.\", \"foodIds\": [5]}\n```\nEnjoy.");

        assertTrue(parsed.structured());
        assertEquals("Soup it is.", parsed.reply());
        assertEquals(List.of(5L), parsed.foodIds());
    }

    @Test
    void readsJsonSurroundedByProse() {
        final ParsedReply parsed = ChatReplyParser
                .parse("Here you go: {\"reply\": \"Salad.\", \"foodIds\": [9]} hope that helps");

        assertEquals("Salad.", parsed.reply());
        assertEquals(List.of(9L), parsed.foodIds());
    }

    @Test
    void acceptsIdsWrittenAsStringsObjectsAndWholeDecimals() {
        final ParsedReply parsed = ChatReplyParser
                .parse("{\"reply\": \"ok\", \"foodIds\": [\"4\", {\"id\": 6}, 8.0, \"#10\"]}");

        assertEquals(List.of(4L, 6L, 8L, 10L), parsed.foodIds());
    }

    @Test
    void dropsIdsThatAreNotPositiveWholeNumbers() {
        final ParsedReply parsed = ChatReplyParser.parse(
                "{\"reply\": \"ok\", \"foodIds\": [0, -3, 2.5, \"pizza\", null, true, {\"name\": \"x\"}, 12]}");

        assertEquals(List.of(12L), parsed.foodIds());
    }

    @Test
    void removesDuplicateIdsAndKeepsTheirOrder() {
        final ParsedReply parsed = ChatReplyParser.parse("{\"reply\": \"ok\", \"foodIds\": [7, 3, 7, \"3\"]}");

        assertEquals(List.of(7L, 3L), parsed.foodIds());
    }

    @Test
    void acceptsASingleIdInsteadOfAList() {
        assertEquals(List.of(5L), ChatReplyParser.parse("{\"reply\": \"ok\", \"foodIds\": 5}").foodIds());
    }

    @Test
    void acceptsMessageInsteadOfReplyAndSnakeCaseIds() {
        final ParsedReply parsed = ChatReplyParser.parse("{\"message\": \"Hi!\", \"food_ids\": [2]}");

        assertEquals("Hi!", parsed.reply());
        assertEquals(List.of(2L), parsed.foodIds());
    }

    @Test
    void jsonWithoutAReplyGivesAnEmptyReply() {
        final ParsedReply parsed = ChatReplyParser.parse("{\"foodIds\": [1]}");

        assertTrue(parsed.structured());
        assertEquals("", parsed.reply());
        assertEquals(List.of(1L), parsed.foodIds());
    }

    @Test
    void plainTextFallsBackToTextOnly() {
        final ParsedReply parsed = ChatReplyParser.parse("  Hello! How can I help you today?  ");

        assertFalse(parsed.structured());
        assertEquals("Hello! How can I help you today?", parsed.reply());
        assertTrue(parsed.foodIds().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"reply\": \"cut off", "{reply: 'single quotes', foodIds: [1]}", "[1, 2, 3]",
        "\"just a string\"", "I recommend the steak {not json}" })
    void brokenJsonFallsBackToTheRawText(final String raw) {
        final ParsedReply parsed = ChatReplyParser.parse(raw);

        assertFalse(parsed.structured());
        assertEquals(raw.trim(), parsed.reply());
        assertTrue(parsed.foodIds().isEmpty(), "ids from broken JSON must never be trusted");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\n" })
    void noAnswerGivesAnEmptyReply(final String raw) {
        final ParsedReply parsed = ChatReplyParser.parse(raw);

        assertEquals("", parsed.reply());
        assertTrue(parsed.foodIds().isEmpty());
    }
}
