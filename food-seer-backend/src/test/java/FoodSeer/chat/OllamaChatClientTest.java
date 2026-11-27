package FoodSeer.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import FoodSeer.exception.ChatUnavailableException;

/**
 * Runs {@link OllamaChatClient} against a real local HTTP server standing in
 * for Ollama, so timeouts and refused connections are real, not mocked.
 */
class OllamaChatClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a fake Ollama that answers /api/chat with the given status and body after a delay. */
    private String startFakeOllama(final int status, final String body, final long delayMs,
            final AtomicReference<String> requestBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            if (requestBody != null) {
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            try {
                Thread.sleep(delayMs);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (final IOException clientGaveUp) {
                // The client timed out and closed the connection first.
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void returnsTheModelsTextAndSendsModelAndMessages() throws Exception {
        final AtomicReference<String> sent = new AtomicReference<>();
        final String url = startFakeOllama(200,
                "{\"model\":\"m\",\"message\":{\"role\":\"assistant\",\"content\":\"Try the salad.\"},\"done\":true}", 0,
                sent);
        final OllamaChatClient client = new OllamaChatClient(url + "/", "test-model:1b", 2000);

        final String answer = client.complete(List.of(ChatTurn.system("Be brief."), ChatTurn.user("hungry")));

        assertEquals("Try the salad.", answer);
        final JsonNode request = new ObjectMapper().readTree(sent.get());
        assertEquals("test-model:1b", request.get("model").asText());
        assertEquals(false, request.get("stream").asBoolean());
        assertEquals("system", request.get("messages").get(0).get("role").asText());
        assertEquals("hungry", request.get("messages").get(1).get("content").asText());
    }

    @Test
    void asksForJsonInTheReplyAndFoodIdsShape() throws Exception {
        final AtomicReference<String> sent = new AtomicReference<>();
        final String url = startFakeOllama(200, "{\"message\":{\"content\":\"{}\"}}", 0, sent);

        new OllamaChatClient(url, "m", 2000).complete(List.of(ChatTurn.user("hi")));

        final JsonNode request = new ObjectMapper().readTree(sent.get());
        final JsonNode format = request.get("format");
        assertEquals("object", format.get("type").asText());
        assertEquals("string", format.get("properties").get("reply").get("type").asText());
        assertEquals("integer", format.get("properties").get("foodIds").get("items").get("type").asText());
        assertEquals("[\"reply\",\"foodIds\"]", format.get("required").toString());
        assertEquals(OllamaChatClient.TEMPERATURE, request.get("options").get("temperature").asDouble());
    }

    @Test
    void trailingSlashesInTheUrlAreIgnored() {
        final OllamaChatClient client = new OllamaChatClient("http://localhost:11434//", "m", 1000);
        assertEquals("http://localhost:11434/api/chat", client.getChatUrl());
    }

    @Test
    void aStoppedServerIsReportedAsUnavailable() throws Exception {
        final OllamaChatClient client = new OllamaChatClient("http://127.0.0.1:" + unusedPort(), "m", 2000);

        final ChatUnavailableException e = assertThrows(ChatUnavailableException.class,
                () -> client.complete(List.of(ChatTurn.user("hi"))));
        assertEquals(OllamaChatClient.UNAVAILABLE, e.getMessage());
    }

    @Test
    void aSlowModelTimesOutInsteadOfHangingTheRequest() throws Exception {
        final String url = startFakeOllama(200, "{\"message\":{\"content\":\"late\"}}", 3000, null);
        final OllamaChatClient client = new OllamaChatClient(url, "m", 300);

        final long start = System.nanoTime();
        assertThrows(ChatUnavailableException.class, () -> client.complete(List.of(ChatTurn.user("hi"))));
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 2500, "the client waited " + elapsedMs + " ms, longer than its 300 ms timeout");
    }

    @Test
    void aMissingModelNamesTheModelToPull() throws Exception {
        final String url = startFakeOllama(404, "{\"error\":\"model 'qwen2.5:1.5b' not found\"}", 0, null);
        final OllamaChatClient client = new OllamaChatClient(url, "qwen2.5:1.5b", 2000);

        final ChatUnavailableException e = assertThrows(ChatUnavailableException.class,
                () -> client.complete(List.of(ChatTurn.user("hi"))));
        assertTrue(e.getMessage().contains("ollama pull qwen2.5:1.5b"), e.getMessage());
    }

    @Test
    void aServerErrorIsReportedAsUnavailable() throws Exception {
        final String url = startFakeOllama(500, "{\"error\":\"out of memory\"}", 0, null);
        final OllamaChatClient client = new OllamaChatClient(url, "m", 2000);

        final ChatUnavailableException e = assertThrows(ChatUnavailableException.class,
                () -> client.complete(List.of(ChatTurn.user("hi"))));
        assertEquals(OllamaChatClient.UNAVAILABLE, e.getMessage());
    }

    @Test
    void anAnswerWithoutContentIsReportedAsUnavailable() throws Exception {
        final String url = startFakeOllama(200, "{\"done\":true}", 0, null);
        final OllamaChatClient client = new OllamaChatClient(url, "m", 2000);

        assertThrows(ChatUnavailableException.class, () -> client.complete(List.of(ChatTurn.user("hi"))));
    }

    @Test
    void anAnswerThatIsNotJsonIsReportedAsUnavailable() throws Exception {
        final String url = startFakeOllama(200, "<html>proxy error</html>", 0, null);
        final OllamaChatClient client = new OllamaChatClient(url, "m", 2000);

        assertThrows(ChatUnavailableException.class, () -> client.complete(List.of(ChatTurn.user("hi"))));
    }

    @Test
    void badSettingsAreRejectedAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new OllamaChatClient(" ", "m", 1000));
        assertThrows(IllegalArgumentException.class, () -> new OllamaChatClient("http://x", "", 1000));
        assertThrows(IllegalArgumentException.class, () -> new OllamaChatClient("http://x", "m", 0));
    }

    @Test
    void theDefaultModelIsTheOneInstallMdSaysToPull() {
        assertEquals("qwen2.5:1.5b", OllamaChatClient.DEFAULT_MODEL);
    }
}
