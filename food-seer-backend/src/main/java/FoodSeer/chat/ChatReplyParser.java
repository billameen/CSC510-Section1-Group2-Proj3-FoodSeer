package FoodSeer.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads the model's answer, which is asked to be
 * {@code {"reply": "...", "foodIds": [..]}}.
 *
 * Small local models often get the format a little wrong, so this accepts the
 * usual mistakes: JSON wrapped in a Markdown code fence or in prose, ids sent
 * as strings ("7") or objects ({"id": 7}), and "message" instead of "reply".
 * If no JSON object can be found at all, the whole answer is used as the reply
 * and no foods are suggested. Parsing never throws.
 */
public final class ChatReplyParser {

    /** Matches a ```json ... ``` or ``` ... ``` block. */
    private static final Pattern CODE_FENCE = Pattern.compile("```(?:json|JSON)?\\s*(.*?)```", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * What the model said.
     *
     * @param reply
     *            the text to show, may be empty if the model sent only ids
     * @param foodIds
     *            the suggested dish ids, in order, without duplicates; not yet
     *            checked against the menu
     * @param structured
     *            true if the answer was JSON in the asked-for shape
     */
    public record ParsedReply(String reply, List<Long> foodIds, boolean structured) {
    }

    private ChatReplyParser() {
        // utility class
    }

    /**
     * Parses the model's raw answer.
     *
     * @param raw
     *            what the model returned, may be null
     * @return the reply and suggested ids, never null
     */
    public static ParsedReply parse(final String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParsedReply("", List.of(), false);
        }
        final JsonNode object = findJsonObject(raw.trim());
        if (object == null) {
            return new ParsedReply(raw.trim(), List.of(), false);
        }
        return new ParsedReply(readReply(object), readFoodIds(object), true);
    }

    /**
     * Finds the answer's JSON object: the whole text, then a code fence, then
     * the span from the first "{" to the last "}".
     */
    private static JsonNode findJsonObject(final String text) {
        final JsonNode whole = readObject(text);
        if (whole != null) {
            return whole;
        }
        final Matcher fence = CODE_FENCE.matcher(text);
        while (fence.find()) {
            final JsonNode fenced = readObject(fence.group(1).trim());
            if (fenced != null) {
                return fenced;
            }
        }
        final int open = text.indexOf('{');
        final int close = text.lastIndexOf('}');
        if (open >= 0 && close > open) {
            return readObject(text.substring(open, close + 1));
        }
        return null;
    }

    /** Parses text as a JSON object, or returns null if it is not one. */
    private static JsonNode readObject(final String text) {
        try {
            final JsonNode node = MAPPER.readTree(text);
            return node != null && node.isObject() ? node : null;
        } catch (final Exception notJson) {
            return null;
        }
    }

    private static String readReply(final JsonNode object) {
        for (final String key : new String[] { "reply", "message", "text" }) {
            final JsonNode value = object.get(key);
            if (value != null && value.isTextual()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private static List<Long> readFoodIds(final JsonNode object) {
        JsonNode ids = null;
        for (final String key : new String[] { "foodIds", "food_ids", "foodIDs", "ids" }) {
            if (object.has(key)) {
                ids = object.get(key);
                break;
            }
        }
        final Set<Long> result = new LinkedHashSet<>();
        if (ids == null || ids.isNull()) {
            return List.of();
        }
        if (!ids.isArray()) {
            addId(ids, result);
            return new ArrayList<>(result);
        }
        for (final JsonNode id : ids) {
            addId(id, result);
        }
        return new ArrayList<>(result);
    }

    /** Adds one id if it is a positive whole number, however it was written. */
    private static void addId(final JsonNode node, final Set<Long> result) {
        JsonNode value = node;
        if (value.isObject()) {
            value = value.get("id");
            if (value == null) {
                return;
            }
        }
        Long id = null;
        if (value.isIntegralNumber()) {
            id = value.asLong();
        } else if (value.isNumber() && value.asDouble() == Math.rint(value.asDouble())) {
            id = (long) value.asDouble();
        } else if (value.isTextual()) {
            final String text = value.asText().trim().replaceFirst("^#", "");
            if (text.matches("\\d{1,18}")) {
                id = Long.parseLong(text);
            }
        }
        if (id != null && id > 0) {
            result.add(id);
        }
    }
}
