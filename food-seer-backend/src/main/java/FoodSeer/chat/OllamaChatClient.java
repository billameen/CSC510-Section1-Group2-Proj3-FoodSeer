package FoodSeer.chat;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import FoodSeer.exception.ChatUnavailableException;

/**
 * Talks to a local Ollama server through its /api/chat endpoint.
 *
 * Settings, each of which can also be set as an environment variable:
 * <ul>
 * <li>{@code foodseer.ollama.url} ({@code FOODSEER_OLLAMA_URL}), default
 * http://localhost:11434</li>
 * <li>{@code foodseer.ollama.model} ({@code FOODSEER_OLLAMA_MODEL}), default
 * qwen2.5:1.5b</li>
 * <li>{@code foodseer.ollama.timeout-ms} ({@code FOODSEER_OLLAMA_TIMEOUTMS}),
 * default 60000: the longest wait for an answer. Connecting gives up after
 * this or 5 seconds, whichever is shorter.</li>
 * </ul>
 */
@Component
public class OllamaChatClient implements ChatModelClient {

    /** The model INSTALL.md tells people to pull. */
    public static final String DEFAULT_MODEL = "qwen2.5:1.5b";

    /** Longest wait for a connection, even when the answer timeout is longer. */
    static final int MAX_CONNECT_TIMEOUT_MS = 5000;

    /**
     * Sampling temperature. Low, because the answer must be valid JSON that
     * only uses ids from the menu, not creative writing.
     */
    static final double TEMPERATURE = 0.2;

    /** Shown when the model cannot answer for any reason we do not name. */
    static final String UNAVAILABLE = "The food assistant is unavailable right now. Please try again in a moment.";

    private static final Logger logger = LoggerFactory.getLogger(OllamaChatClient.class);

    private final String chatUrl;
    private final String model;
    private final int timeoutMs;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates the client.
     *
     * @param baseUrl
     *            where Ollama listens, for example http://localhost:11434
     * @param model
     *            the model to ask, for example qwen2.5:1.5b
     * @param timeoutMs
     *            the longest wait for an answer, in milliseconds
     */
    public OllamaChatClient(@Value("${foodseer.ollama.url:http://localhost:11434}") final String baseUrl,
            @Value("${foodseer.ollama.model:" + DEFAULT_MODEL + "}") final String model,
            @Value("${foodseer.ollama.timeout-ms:60000}") final int timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("foodseer.ollama.url must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("foodseer.ollama.model must not be blank");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("foodseer.ollama.timeout-ms must be positive");
        }
        this.chatUrl = stripTrailingSlashes(baseUrl.trim()) + "/api/chat";
        this.model = model.trim();
        this.timeoutMs = timeoutMs;

        final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(timeoutMs, MAX_CONNECT_TIMEOUT_MS));
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * @return the full /api/chat URL this client posts to
     */
    public String getChatUrl() {
        return chatUrl;
    }

    /**
     * @return the model this client asks
     */
    public String getModel() {
        return model;
    }

    /**
     * @return the longest wait for an answer, in milliseconds
     */
    public int getTimeoutMs() {
        return timeoutMs;
    }

    @Override
    public String complete(final List<ChatTurn> conversation) {
        final String body = buildRequestBody(conversation);
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        final String response;
        try {
            response = restTemplate.postForObject(chatUrl, new HttpEntity<>(body, headers), String.class);
        } catch (final ResourceAccessException e) {
            // Connection refused, unknown host, or no answer within the timeout.
            logger.warn("Ollama at {} did not answer: {}", chatUrl, e.getMessage());
            throw new ChatUnavailableException(UNAVAILABLE, e);
        } catch (final HttpStatusCodeException e) {
            logger.warn("Ollama at {} answered {} for model {}: {}", chatUrl, e.getStatusCode().value(), model,
                    e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 404) {
                throw new ChatUnavailableException("The food assistant's model (" + model
                        + ") is not installed on the server. Run: ollama pull " + model, e);
            }
            throw new ChatUnavailableException(UNAVAILABLE, e);
        } catch (final RestClientException e) {
            logger.warn("Calling Ollama at {} failed: {}", chatUrl, e.getMessage());
            throw new ChatUnavailableException(UNAVAILABLE, e);
        }

        return readContent(response);
    }

    /**
     * Builds the JSON body for /api/chat.
     *
     * @param conversation
     *            the messages to send
     * @return the request body
     */
    String buildRequestBody(final List<ChatTurn> conversation) {
        final ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.put("stream", false);
        request.set("format", replySchema());
        request.putObject("options").put("temperature", TEMPERATURE);
        final ArrayNode messages = request.putArray("messages");
        for (final ChatTurn turn : conversation) {
            final ObjectNode message = messages.addObject();
            message.put("role", turn.role());
            message.put("content", turn.content());
        }
        try {
            return objectMapper.writeValueAsString(request);
        } catch (final com.fasterxml.jackson.core.JsonProcessingException e) {
            // Only plain strings go in, so this cannot happen in practice.
            throw new IllegalStateException("Could not build the Ollama request", e);
        }
    }

    /**
     * The JSON schema Ollama's structured output uses to constrain the answer:
     * {"reply": string, "foodIds": [integer]}. {@link ChatReplyParser} still
     * parses defensively, because older Ollama versions and small models do
     * not always follow it.
     *
     * @return the schema
     */
    ObjectNode replySchema() {
        final ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        final ObjectNode properties = schema.putObject("properties");
        properties.putObject("reply").put("type", "string");
        final ObjectNode foodIds = properties.putObject("foodIds");
        foodIds.put("type", "array");
        foodIds.putObject("items").put("type", "integer");
        schema.putArray("required").add("reply").add("foodIds");
        return schema;
    }

    /**
     * Pulls message.content out of an Ollama /api/chat response.
     *
     * @param response
     *            the raw response body
     * @return the model's text
     */
    String readContent(final String response) {
        if (response == null || response.isBlank()) {
            throw new ChatUnavailableException("The food assistant sent back an empty answer. Please try again.");
        }
        try {
            final JsonNode content = objectMapper.readTree(response).path("message").path("content");
            if (!content.isTextual()) {
                throw new ChatUnavailableException(
                        "The food assistant sent back an answer FoodSeer could not read. Please try again.");
            }
            return content.asText();
        } catch (final com.fasterxml.jackson.core.JsonProcessingException e) {
            logger.warn("Ollama sent a response that is not JSON: {}", e.getMessage());
            throw new ChatUnavailableException(
                    "The food assistant sent back an answer FoodSeer could not read. Please try again.", e);
        }
    }

    private static String stripTrailingSlashes(final String url) {
        String result = url;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
