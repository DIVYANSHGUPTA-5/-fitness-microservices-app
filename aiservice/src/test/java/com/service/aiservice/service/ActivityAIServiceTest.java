package com.service.aiservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.service.aiservice.model.Activity;
import com.service.aiservice.model.Recommendation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityAIServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiService gemini = mock(GeminiService.class);
    private final ActivityAIService service = new ActivityAIService(gemini);

    private Activity walking() {
        Activity activity = new Activity();
        activity.setId("act-1");
        activity.setUserId("user-1");
        activity.setType("WALKING");
        activity.setDuration(30);
        activity.setCaloriesBurned(200);
        return activity;
    }

    /** The JSON Gemini is asked for. */
    private String structuredAnswer() throws Exception {
        return mapper.writeValueAsString(Map.of(
                "analysis", "First paragraph about the walk.\n\nSecond paragraph about intensity.",
                "performanceInsights", List.of("Intensity: about 6.7 kcal per minute is a steady pace.", "Duration: 30 minutes is a solid base."),
                "improvements", List.of("Track pace: recording distance lets you compare walks."),
                "nextSteps", List.of("Add 5 minutes: extend the walk gradually."),
                "trainingRecommendations", List.of("Brisk intervals: alternate easy and brisk minutes."),
                "recovery", List.of("Hydration: drink water after the walk."),
                "nutrition", List.of("Before: a banana.", "After: yoghurt and fruit."),
                "safetyGuidelines", List.of("Footwear: wear supportive shoes."),
                "summary", "A steady walk. Build gradually."));
    }

    /** The envelope the Gemini API wraps around the model's text. */
    private String envelope(String text) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode parts = root.putArray("candidates").addObject().putObject("content").putArray("parts");
        parts.addObject().put("text", text);
        ((ObjectNode) root.get("candidates").get(0)).put("finishReason", "STOP");
        root.putObject("usageMetadata").put("promptTokenCount", 400).put("candidatesTokenCount", 900);
        return mapper.writeValueAsString(root);
    }

    @Test
    void mapsTheStructuredAnswerOntoTheRecommendation() throws Exception {
        when(gemini.getAnswer(anyString())).thenReturn(envelope(structuredAnswer()));

        Recommendation result = service.generateRecommendation(walking());

        assertThat(result.getAiGenerated()).isTrue();
        assertThat(result.getActivityId()).isEqualTo("act-1");
        assertThat(result.getUserId()).isEqualTo("user-1");
        assertThat(result.getActivityType()).isEqualTo("WALKING");
        assertThat(result.getRecommendation()).contains("First paragraph").contains("\n\n").contains("Second paragraph");
        assertThat(result.getPerformanceInsights()).hasSize(2);
        assertThat(result.getImprovements()).containsExactly("Track pace: recording distance lets you compare walks.");
        assertThat(result.getNextSteps()).hasSize(1);
        assertThat(result.getSuggestions()).containsExactly("Brisk intervals: alternate easy and brisk minutes.");
        assertThat(result.getRecovery()).hasSize(1);
        assertThat(result.getNutrition()).hasSize(2);
        assertThat(result.getSafety()).containsExactly("Footwear: wear supportive shoes.");
        assertThat(result.getSummary()).isEqualTo("A steady walk. Build gradually.");
        assertThat(result.getCreatedAt()).isNotNull();
    }

    @Test
    void acceptsAnswersWrappedInMarkdownFences() throws Exception {
        when(gemini.getAnswer(anyString())).thenReturn(envelope("```json\n" + structuredAnswer() + "\n```"));

        assertThat(service.generateRecommendation(walking()).getAiGenerated()).isTrue();
    }

    @Test
    void ignoresThoughtPartsAndAcceptsObjectItems() throws Exception {
        ObjectNode root = mapper.createObjectNode();
        ArrayNode parts = root.putArray("candidates").addObject().putObject("content").putArray("parts");
        parts.addObject().put("thought", true).put("text", "internal reasoning that is not JSON");
        parts.addObject().put("text", """
                {"analysis":"Plain analysis.",
                 "improvements":[{"title":"Pace","description":"Track it."}],
                 "nextSteps":["Walk again soon."]}""");
        when(gemini.getAnswer(anyString())).thenReturn(mapper.writeValueAsString(root));

        Recommendation result = service.generateRecommendation(walking());

        assertThat(result.getAiGenerated()).isTrue();
        assertThat(result.getImprovements()).containsExactly("Pace: Track it.");
        assertThat(result.getPerformanceInsights()).isEmpty(); // missing sections are empty, never invented
    }

    @Test
    void asksAgainOnceWhenTheFirstAnswerIsNotUsable() throws Exception {
        when(gemini.getAnswer(anyString())).thenReturn(envelope("this is not json"), envelope(structuredAnswer()));

        assertThat(service.generateRecommendation(walking()).getAiGenerated()).isTrue();
        verify(gemini, times(2)).getAnswer(anyString());
    }

    @Test
    void usesTheProfessionalFallbackWhenGeminiIsTemporarilyUnavailable() {
        when(gemini.getAnswer(anyString())).thenThrow(new GeminiService.GeminiException("gemini-x: HTTP 503 UNAVAILABLE - overloaded", false));

        Recommendation result = service.generateRecommendation(walking());

        assertThat(result.getAiGenerated()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("gemini-x: HTTP 503 UNAVAILABLE - overloaded");
        assertThat(result.getRecommendation()).isEqualTo(ActivityAIService.UNAVAILABLE_MESSAGE)
                .doesNotContain("Unable to generate detailed analysis");
        assertThat(result.getActivityId()).isEqualTo("act-1");
        assertThat(result.getImprovements()).isNull();
        assertThat(result.getSuggestions()).isNull();
        assertThat(result.getSafety()).isNull();
        verify(gemini, times(1)).getAnswer(anyString()); // the failed call was already retried inside GeminiService
    }

    @Test
    void configurationProblemsAreReportedAsSuchInsteadOfLookingLikeATemporaryOutage() {
        String reason = "gemini-3.8-flash: HTTP 403 PERMISSION_DENIED - Your project has been denied access. Please contact support.";
        when(gemini.getAnswer(anyString())).thenThrow(new GeminiService.GeminiException(reason, true));

        Recommendation result = service.generateRecommendation(walking());

        assertThat(result.getAiGenerated()).isFalse();
        assertThat(result.getRecommendation()).isEqualTo(ActivityAIService.CONFIGURATION_PROBLEM_MESSAGE)
                .doesNotContain("try again shortly");
        assertThat(result.getFailureReason()).isEqualTo(reason);
        verify(gemini, times(1)).getAnswer(anyString()); // retrying cannot fix it
    }

    @Test
    void usesTheFallbackWhenGeminiBlocksTheRequest() throws Exception {
        String blocked = "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}";
        when(gemini.getAnswer(anyString())).thenReturn(blocked);

        Recommendation result = service.generateRecommendation(walking());

        assertThat(result.getAiGenerated()).isFalse();
        verify(gemini, times(2)).getAnswer(anyString());
    }

    @Test
    void usesTheFallbackWhenTheAnswerLacksAnAnalysis() throws Exception {
        when(gemini.getAnswer(anyString())).thenReturn(envelope("{\"improvements\":[\"Something: here.\"]}"));

        assertThat(service.generateRecommendation(walking()).getAiGenerated()).isFalse();
    }

    @Test
    void promptContainsTheRealActivityDataAndTheRequiredInstructions() throws Exception {
        when(gemini.getAnswer(anyString())).thenReturn(envelope(structuredAnswer()));

        service.generateRecommendation(walking());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).getAnswer(prompt.capture());
        assertThat(prompt.getValue())
                .contains("You are an AI fitness analysis assistant.")
                .contains("using only the activity information provided")
                .contains("Do not give generic one-line advice.")
                .contains("If some information is unavailable, do not invent it.")
                .contains("Activity type: WALKING")
                .contains("Duration: 30 minutes")
                .contains("Calories burned: 200 kcal")
                .contains("6.7 kcal per minute")
                .contains("Start time: not recorded")
                .contains("Additional metrics: none recorded")
                .contains("Walking duration and consistency")
                .contains("\"performanceInsights\"").contains("\"trainingRecommendations\"").contains("\"safetyGuidelines\"")
                .contains("Return valid JSON only")
                .contains("Do not make medical claims");
    }

    @Test
    void promptReportsMissingValuesInsteadOfInventingThem() throws Exception {
        when(gemini.getAnswer(anyString())).thenReturn(envelope(structuredAnswer()));
        Activity activity = walking();
        activity.setType("WEIGHT_TRAINING");
        activity.setDuration(null);
        activity.setAdditionalMetrics(Map.of("sets", 12));

        service.generateRecommendation(activity);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).getAnswer(prompt.capture());
        assertThat(prompt.getValue())
                .contains("Duration: not recorded")
                .contains("Calories per minute (calculated from the two values above): not available")
                .contains("{\"sets\":12}")
                .contains("progressive overload");
    }
}
