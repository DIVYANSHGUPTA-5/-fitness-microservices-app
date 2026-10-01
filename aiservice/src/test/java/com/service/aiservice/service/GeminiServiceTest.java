package com.service.aiservice.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a local HTTP server that answers the way the Gemini API does (success and its real error payloads). */
class GeminiServiceTest {

    private static final String KEY = "test-secret-key-123";
    private static final String OK = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]},\"finishReason\":\"STOP\"}]}";
    private static final String INVALID_KEY = "{\"error\":{\"code\":400,\"message\":\"API key not valid. Please pass a valid API key.\","
            + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}";
    private static final String MODEL_GONE = "{\"error\":{\"code\":404,\"message\":\"models/gemini-2.5-flash is no longer available to new users.\","
            + "\"status\":\"NOT_FOUND\"}}";
    private static final String OVERLOADED = "{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\",\"status\":\"UNAVAILABLE\"}}";
    private static final String DENIED = "{\"error\":{\"code\":403,\"message\":\"Your project has been denied access. Please contact support.\","
            + "\"status\":\"PERMISSION_DENIED\"}}";
    private static final String QUOTA = "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota.\",\"status\":\"RESOURCE_EXHAUSTED\"}}";

    private record Reply(int status, String body) {}
    private record Seen(String path, String query, String body) {}

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private BiFunction<Seen, Integer, Reply> router = (request, number) -> new Reply(200, OK);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Seen request = new Seen(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(), body);
            seen.add(request);
            Reply reply = router.apply(request, seen.size());
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(GeminiService.class)).addAppender(logs);
    }

    @AfterEach
    void stopServer() {
        ((Logger) LoggerFactory.getLogger(GeminiService.class)).detachAppender(logs);
        server.stop(0);
    }

    private GeminiService newService(String key, String fallbackModels) {
        GeminiService service = new GeminiService(WebClient.builder().build());
        ReflectionTestUtils.setField(service, "geminiApiUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models/gemini-2.5-flash:generateContent?key=");
        ReflectionTestUtils.setField(service, "geminiApiKey", key);
        ReflectionTestUtils.setField(service, "fallbackModels", fallbackModels);
        service.retryBackoff = Duration.ofMillis(10);
        return service;
    }

    private String logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
    }

    @Test
    void sendsPromptAndKeyInGeminiFormatAndReturnsTheResponse() {
        String response = newService(KEY, "").getAnswer("Analyze this walk");

        assertThat(response).isEqualTo(OK);
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).path()).isEqualTo("/v1beta/models/gemini-2.5-flash:generateContent");
        assertThat(seen.get(0).query()).isEqualTo("key=" + KEY);
        assertThat(seen.get(0).body())
                .contains("\"contents\":[{\"parts\":[{\"text\":\"Analyze this walk\"}]}]")
                .contains("\"generationConfig\":{\"responseMimeType\":\"application/json\"}");
    }

    @Test
    void invalidKeyIsLoggedWithGoogleReasonNotRetriedAndNeverLeaksTheKey() {
        router = (request, number) -> new Reply(400, INVALID_KEY);

        assertThatThrownBy(() -> newService(KEY, "gemini-3.8-flash").getAnswer("prompt"))
                .isInstanceOfSatisfying(GeminiService.GeminiException.class, e -> {
                    assertThat(e.isConfigurationProblem()).isTrue();
                    assertThat(e.getMessage()).contains("HTTP 400", "API key not valid").doesNotContain(KEY);
                });

        assertThat(seen).as("a bad key is the same for every model, so nothing is retried or re-sent").hasSize(1);
        assertThat(logged())
                .contains("HTTP 400", "INVALID_ARGUMENT", "API key not valid", "GEMINI_API_KEY")
                .doesNotContain(KEY);
    }

    @Test
    void temporaryErrorsAreRetried() {
        router = (request, number) -> number < 3 ? new Reply(503, OVERLOADED) : new Reply(200, OK);

        assertThat(newService(KEY, "").getAnswer("prompt")).isEqualTo(OK);

        assertThat(seen).hasSize(3);
        assertThat(logged()).contains("retry 1 of 2", "retry 2 of 2");
    }

    @Test
    void unavailableModelFallsBackToTheNextModelAndIsSkippedAfterwards() {
        router = (request, number) -> request.path().contains("gemini-2.5-flash") ? new Reply(404, MODEL_GONE) : new Reply(200, OK);
        GeminiService service = newService(KEY, "gemini-3.8-flash");

        assertThat(service.getAnswer("first")).isEqualTo(OK);
        assertThat(seen).extracting(Seen::path).containsExactly(
                "/v1beta/models/gemini-2.5-flash:generateContent", "/v1beta/models/gemini-3.8-flash:generateContent");
        assertThat(logged()).contains("HTTP 404", "no longer available", "Trying the next configured Gemini model");

        assertThat(service.getAnswer("second")).isEqualTo(OK);
        assertThat(seen).as("the model known to be unavailable is not asked again").hasSize(3);
        assertThat(seen.get(2).path()).isEqualTo("/v1beta/models/gemini-3.8-flash:generateContent");
    }

    @Test
    void quotaExhaustedOnOneModelTriesTheNextOne() {
        router = (request, number) -> request.path().contains("gemini-2.5-flash") ? new Reply(429, QUOTA) : new Reply(200, OK);

        assertThat(newService(KEY, "gemini-3.8-flash").getAnswer("prompt")).isEqualTo(OK);

        assertThat(seen).hasSize(4); // 1 call + 2 retries on the first model, then the fallback model
        assertThat(logged()).contains("HTTP 429", "RESOURCE_EXHAUSTED");
    }

    @Test
    void failureOfTheOnlyModelIsThrownWithTheRealReasonAndLogged() {
        router = (request, number) -> new Reply(404, MODEL_GONE);

        assertThatThrownBy(() -> newService(KEY, "").getAnswer("prompt"))
                .isInstanceOfSatisfying(GeminiService.GeminiException.class, e -> {
                    assertThat(e.isConfigurationProblem()).isTrue();
                    assertThat(e.getMessage()).contains("gemini-2.5-flash", "HTTP 404", "no longer available to new users");
                });

        assertThat(logged()).contains("HTTP 404", "no longer available to new users", "GEMINI_MODEL");
    }

    @Test
    void missingKeySendsNothingAndSaysWhichVariableToSet() {
        assertThatThrownBy(() -> newService(GeminiService.KEY_PLACEHOLDER, "").getAnswer("prompt"))
                .isInstanceOfSatisfying(GeminiService.GeminiException.class, e -> {
                    assertThat(e.isConfigurationProblem()).isTrue();
                    assertThat(e.getMessage()).contains("GEMINI_API_KEY is not set");
                });

        assertThat(seen).isEmpty();
        assertThat(logged()).contains("GEMINI_API_KEY is not set");
    }

    @Test
    void projectDeniedOnEveryModelIsReportedAsAConfigurationProblemWithEachReason() {
        router = (request, number) -> new Reply(403, DENIED);

        assertThatThrownBy(() -> newService(KEY, "gemini-3.5-flash").getAnswer("prompt"))
                .isInstanceOfSatisfying(GeminiService.GeminiException.class, e -> {
                    assertThat(e.isConfigurationProblem()).isTrue();
                    assertThat(e.getMessage()).contains("gemini-2.5-flash: HTTP 403 PERMISSION_DENIED", "gemini-3.5-flash: HTTP 403 PERMISSION_DENIED",
                            "Your project has been denied access");
                });

        assertThat(seen).as("a permission problem is not retried, but every configured model is tried once").hasSize(2);
    }

    @Test
    void temporaryErrorsThatPersistAreNotReportedAsConfigurationProblems() {
        router = (request, number) -> new Reply(503, OVERLOADED);

        assertThatThrownBy(() -> newService(KEY, "").getAnswer("prompt"))
                .isInstanceOfSatisfying(GeminiService.GeminiException.class, e -> {
                    assertThat(e.isConfigurationProblem()).isFalse();
                    assertThat(e.getMessage()).contains("HTTP 503", "The model is overloaded");
                });

        assertThat(seen).hasSize(3); // first call + 2 retries
    }
}
