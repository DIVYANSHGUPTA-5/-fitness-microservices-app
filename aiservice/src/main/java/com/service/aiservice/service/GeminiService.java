package com.service.aiservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GeminiService {

    /** Value of gemini.api.key when GEMINI_API_KEY is not set (see ai-service.yaml). */
    static final String KEY_PLACEHOLDER = "default_dummy_key";

    private static final int MAX_RETRIES = 2;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration UNAVAILABLE_MODEL_TTL = Duration.ofMinutes(10);
    private static final Pattern MODEL_IN_URL = Pattern.compile("/models/([^:/?]+)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    // comma separated model names that are tried, in order, when the configured model cannot be used with this key
    @Value("${gemini.api.fallback-models:}")
    private String fallbackModels;

    // model name -> time until which it is skipped because Gemini said it is not available for this key
    private final Map<String, Long> unavailableUntil = new ConcurrentHashMap<>();
    private final Map<String, String> unavailableReason = new ConcurrentHashMap<>();

    Duration retryBackoff = Duration.ofSeconds(2); // package-private so tests can shorten it

    public GeminiService(WebClient webClient) {
        this.webClient = webClient;
    }

    @PostConstruct
    void logConfiguration() {
        if (isKeyMissing()) {
            log.error("GEMINI_API_KEY is not set (the placeholder '{}' is in use), so no AI recommendation can be generated. "
                    + "Start ai-service with the GEMINI_API_KEY environment variable set to a key from https://aistudio.google.com/apikey",
                    KEY_PLACEHOLDER);
        } else {
            log.info("Gemini configured: model={}, fallback models=[{}], API key present ({} characters)",
                    modelOf(geminiApiUrl), fallbackModels, geminiApiKey.trim().length());
        }
    }

    /** Why no answer could be obtained. A configuration problem is one that retrying will not fix (key, permission, model access). */
    public static class GeminiException extends RuntimeException {
        private final boolean configurationProblem;

        public GeminiException(String message, boolean configurationProblem) {
            super(message);
            this.configurationProblem = configurationProblem;
        }

        public boolean isConfigurationProblem() {
            return configurationProblem;
        }
    }

    /**
     * Sends the prompt to Gemini and returns the raw JSON response.
     * Every failure is logged with Gemini's own reason (HTTP status and error message, never the API key) and then
     * thrown as a {@link GeminiException} carrying the same reason, so it can be shown instead of being hidden.
     */
    public String getAnswer(String prompt) {
        if (isKeyMissing()) {
            log.error("Gemini request NOT sent: GEMINI_API_KEY is not set. Start ai-service with the GEMINI_API_KEY environment "
                    + "variable set to a key from https://aistudio.google.com/apikey and try again.");
            throw new GeminiException("GEMINI_API_KEY is not set on the ai-service", true);
        }

        List<String> urls = candidateUrls();
        List<String> failures = new ArrayList<>();
        boolean configurationProblem = false;
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            String model = modelOf(url);
            boolean lastCandidate = i == urls.size() - 1;

            if (!lastCandidate && isTemporarilyUnavailable(model)) {
                log.info("Skipping Gemini model '{}': it was recently reported as not available for this API key", model);
                failures.add(model + ": " + unavailableReason.get(model) + " (seen recently)");
                configurationProblem = true;
                continue;
            }

            long start = System.currentTimeMillis();
            try {
                log.info("Calling Gemini model '{}' ({} prompt characters)", model, prompt.length());
                String response = send(url, prompt);
                if (response == null || response.isBlank()) {
                    log.error("Gemini model '{}' returned an empty response body", model);
                    failures.add(model + ": empty response body");
                    break;
                }
                log.info("Gemini model '{}' answered in {} ms ({} characters)", model, System.currentTimeMillis() - start, response.length());
                log.debug("Gemini raw response: {}", response);
                return response;
            } catch (WebClientResponseException e) {
                int status = e.getStatusCode().value();
                GoogleError error = GoogleError.from(e);
                log.error("Gemini call failed for model '{}': HTTP {} {} - {} | {}",
                        model, status, error.status(), redact(error.message()), hintFor(status, error.message()));
                String reason = "HTTP " + status + " " + error.status() + " - " + redact(error.message());
                failures.add(model + ": " + reason);
                configurationProblem |= error.configurationProblem(status);
                if (error.modelUnavailable(status)) {
                    unavailableUntil.put(model, System.currentTimeMillis() + UNAVAILABLE_MODEL_TTL.toMillis());
                    unavailableReason.put(model, reason);
                }
                if (!lastCandidate && error.shouldTryAnotherModel(status)) {
                    log.warn("Trying the next configured Gemini model instead of '{}'", model);
                    continue;
                }
                break;
            } catch (Exception e) {
                Throwable cause = rootCause(e);
                log.error("Gemini call failed for model '{}': {}: {}", model, cause.getClass().getSimpleName(), redact(cause.getMessage()));
                failures.add(model + ": " + cause.getClass().getSimpleName() + " - " + redact(cause.getMessage()));
                break;
            }
        }
        String summary = String.join(" | ", failures);
        throw new GeminiException(summary.length() > 700 ? summary.substring(0, 700) + "..." : summary, configurationProblem);
    }

    private String send(String url, String prompt) {
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                // makes Gemini return raw JSON instead of markdown-fenced text
                "generationConfig", Map.of("responseMimeType", "application/json")
        );

        return webClient.post()
                .uri(url + geminiApiKey.trim())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(REQUEST_TIMEOUT)
                .retryWhen(Retry.backoff(MAX_RETRIES, retryBackoff)
                        .filter(GeminiService::isTransient)
                        .doBeforeRetry(signal -> log.warn("Gemini call failed temporarily ({}); retry {} of {}",
                                describe(signal.failure()), signal.totalRetries() + 1, MAX_RETRIES))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .block();
    }

    /** Rate limits, overload and network hiccups are worth retrying; everything else is not. */
    private static boolean isTransient(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            int status = e.getStatusCode().value();
            return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
        }
        return t instanceof WebClientRequestException;
    }

    private String describe(Throwable t) {
        if (t instanceof WebClientResponseException e) {
            return "HTTP " + e.getStatusCode().value() + " " + redact(GoogleError.from(e).message());
        }
        return t.getClass().getSimpleName() + ": " + redact(t.getMessage());
    }

    private boolean isKeyMissing() {
        return geminiApiKey == null || geminiApiKey.isBlank() || KEY_PLACEHOLDER.equals(geminiApiKey.trim());
    }

    /** The configured URL first, then the same URL with each fallback model name substituted. */
    private List<String> candidateUrls() {
        List<String> urls = new ArrayList<>();
        urls.add(geminiApiUrl);
        Matcher matcher = MODEL_IN_URL.matcher(geminiApiUrl);
        if (matcher.find() && fallbackModels != null) {
            for (String name : fallbackModels.split(",")) {
                String model = name.trim();
                if (model.isEmpty()) {
                    continue;
                }
                String url = geminiApiUrl.substring(0, matcher.start(1)) + model + geminiApiUrl.substring(matcher.end(1));
                if (!urls.contains(url)) {
                    urls.add(url);
                }
            }
        }
        return urls;
    }

    private boolean isTemporarilyUnavailable(String model) {
        Long until = unavailableUntil.get(model);
        return until != null && until > System.currentTimeMillis();
    }

    private static String modelOf(String url) {
        Matcher matcher = MODEL_IN_URL.matcher(url == null ? "" : url);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private String redact(String text) {
        if (text == null || geminiApiKey == null || geminiApiKey.isBlank()) {
            return text;
        }
        return text.replace(geminiApiKey.trim(), "***");
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    private String hintFor(int status, String message) {
        String text = message == null ? "" : message.toLowerCase();
        if (text.contains("api key")) {
            return "the API key is not valid: create one at https://aistudio.google.com/apikey and start ai-service with GEMINI_API_KEY=<key>";
        }
        return switch (status) {
            case 400 -> "Gemini rejected the request; see the message above";
            case 401, 403 -> "the key may not be allowed to call this API (key restrictions, API not enabled for the project, or unsupported region)";
            case 404 -> "the model is not available for this key; set GEMINI_MODEL (or GEMINI_API_URL) to a model the key can use, "
                    + "see GET https://generativelanguage.googleapis.com/v1beta/models?key=<key>";
            case 429 -> "quota or rate limit reached for this key/model; wait and retry, or check the quota in Google AI Studio";
            default -> status >= 500 ? "Gemini is temporarily unavailable or overloaded" : "unexpected response from Gemini";
        };
    }

    /** Gemini's own error payload: {"error": {"code": 400, "message": "...", "status": "INVALID_ARGUMENT"}}. */
    private record GoogleError(String status, String message) {

        static GoogleError from(WebClientResponseException e) {
            String body = e.getResponseBodyAsString();
            try {
                JsonNode error = MAPPER.readTree(body).path("error");
                String message = error.path("message").asText("");
                if (!message.isEmpty()) {
                    return new GoogleError(error.path("status").asText(""), message);
                }
            } catch (Exception ignored) {
                // not JSON; fall through to the raw body
            }
            String raw = body == null || body.isBlank() ? e.getStatusText() : body;
            return new GoogleError("", raw.substring(0, Math.min(raw.length(), 500)));
        }

        private String lower() {
            return message == null ? "" : message.toLowerCase();
        }

        /** Bad key, no permission, unknown/closed model or a rejected request: things retrying cannot fix. */
        boolean configurationProblem(int httpStatus) {
            return lower().contains("api key") || httpStatus == 400 || httpStatus == 401 || httpStatus == 403 || httpStatus == 404;
        }

        boolean modelUnavailable(int httpStatus) {
            if (lower().contains("api key")) {
                return false;
            }
            if (httpStatus == 404) {
                return true;
            }
            return (httpStatus == 400 || httpStatus == 403) && lower().contains("model")
                    && (lower().contains("not found") || lower().contains("not available")
                    || lower().contains("no longer available") || lower().contains("not supported"));
        }

        /** Key and request problems are the same for every model; quota, overload and model-access problems are not. */
        boolean shouldTryAnotherModel(int httpStatus) {
            if (lower().contains("api key") || httpStatus == 401) {
                return false;
            }
            if (httpStatus == 400) {
                return lower().contains("model");
            }
            return true;
        }
    }
}
