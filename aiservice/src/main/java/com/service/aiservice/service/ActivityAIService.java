package com.service.aiservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.service.aiservice.model.Activity;
import com.service.aiservice.model.Recommendation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class ActivityAIService {

    /** Shown instead of a recommendation when Gemini genuinely could not produce one. */
    public static final String UNAVAILABLE_MESSAGE =
            "AI analysis is temporarily unavailable. Your activity has been recorded successfully. "
                    + "Please try again shortly.";

    /** Shown when Gemini rejected the request for a reason retrying cannot fix (key, permission, model access). */
    public static final String CONFIGURATION_PROBLEM_MESSAGE =
            "AI analysis could not be generated because the AI service is not configured correctly "
                    + "(see the technical detail below and the ai-service log). Your activity has been recorded successfully.";

    private static final int MAX_AI_ATTEMPTS = 2;

    private static final String PROMPT_TEMPLATE = """
        You are an AI fitness analysis assistant.

        Analyze the user's completed workout/activity using only the activity information provided.
        Give practical, specific and detailed recommendations.
        Do not give generic one-line advice.
        Your response must be personalized to the activity type and the available metrics.
        If some information is unavailable, do not invent it.

        ACTIVITY DATA
        - Activity type: %s
        - Duration: %s
        - Calories burned: %s
        - Calories per minute (calculated from the two values above): %s
        - Start time: %s
        - Additional metrics: %s

        FOCUS FOR THIS ACTIVITY TYPE
        %s

        RULES
        1. Use only the data above. Never invent distance, pace, speed, heart rate, steps, weight, age, fitness level, health conditions, injuries, goals or workout history. If a metric that would normally matter was not recorded, say so briefly and recommend tracking it.
        2. You may reason from the logged duration and calories (for example calories per minute and what that suggests about intensity), but present such estimates as approximate, never as exact measurements.
        3. Every item in every list must be a complete, useful piece of advice of 2 to 4 sentences (roughly 30 to 70 words) that says what to do and why. Never write short fragments such as "Stay hydrated." Start each item with a short title followed by a colon, for example "Hydration: Drink water before and after your session, and keep sipping throughout the day. ..."
        4. Nutrition and diet: give general, activity-specific guidance only. Cover what to eat before the activity, what to eat after it, general protein and carbohydrate guidance, hydration, and examples of suitable foods. Do not make medical claims, diagnose conditions, recommend supplements or medication, or state exact calorie, protein or fluid targets, because the user's body weight, goals and health are unknown.
        5. Safety guidance must be relevant to this specific activity. Suggest seeing a qualified professional only where it is genuinely appropriate.
        6. Write in a professional, encouraging and direct tone and address the user as "you".

        RESPONSE FORMAT
        Return valid JSON only: no markdown, no code fences and no text before or after the JSON. Use exactly this structure:
        {
          "analysis": "A detailed analysis of 2 to 4 paragraphs separated by \\n\\n.",
          "performanceInsights": ["4 to 5 detailed insights"],
          "improvements": ["3 to 5 detailed improvements, each explaining what to improve and why"],
          "nextSteps": ["3 to 5 specific, actionable next steps"],
          "trainingRecommendations": ["3 to 5 detailed recommendations for future training and progression"],
          "recovery": ["3 to 4 detailed recovery recommendations"],
          "nutrition": ["3 to 5 items covering before the activity, after the activity, hydration and example foods"],
          "safetyGuidelines": ["3 to 5 relevant safety points"],
          "summary": "A detailed but concise summary (3 to 4 sentences) of the most important recommendations."
        }
        """;

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();

    public Recommendation generateRecommendation(Activity activity)
    {
        log.info("Requesting AI analysis for activity {} (type={}, duration={}, calories={})",
                activity.getId(), activity.getType(), activity.getDuration(), activity.getCaloriesBurned());
        String prompt = createPromptForActivity(activity);

        String failureReason = "Gemini's answer could not be read (see the ai-service log)";
        boolean configurationProblem = false;
        for (int attempt = 1; attempt <= MAX_AI_ATTEMPTS; attempt++) {
            String aiResponse;
            try {
                aiResponse = geminiService.getAnswer(prompt);
            } catch (GeminiService.GeminiException e) {
                // GeminiService has already logged the details of the failed call
                failureReason = e.getMessage();
                configurationProblem = e.isConfigurationProblem();
                break;
            }
            Recommendation recommendation = parseRecommendation(activity, aiResponse);
            if (recommendation != null) {
                return recommendation;
            }
            log.warn("Gemini's answer for activity {} could not be used (attempt {} of {})",
                    activity.getId(), attempt, MAX_AI_ATTEMPTS);
        }

        log.error("No AI analysis could be generated for activity {} ({}); storing the '{}' recommendation",
                activity.getId(), failureReason, configurationProblem ? "configuration problem" : "temporarily unavailable");
        return createUnavailableRecommendation(activity, failureReason, configurationProblem);
    }

    /** Returns null (after logging the reason) when the response cannot be turned into a recommendation. */
    private Recommendation parseRecommendation(Activity activity, String aiResponse)
    {
        try {
            JsonNode root = mapper.readTree(aiResponse);
            String finishReason = root.path("candidates").path(0).path("finishReason").asText("unknown");
            JsonNode usage = root.path("usageMetadata");
            log.info("Gemini answer for activity {}: finishReason={}, promptTokens={}, outputTokens={}, thinkingTokens={}",
                    activity.getId(), finishReason, usage.path("promptTokenCount").asText("?"),
                    usage.path("candidatesTokenCount").asText("?"), usage.path("thoughtsTokenCount").asText("0"));

            String text = extractText(root);
            if (text.isBlank()) {
                log.error("Gemini returned no text for activity {} (finishReason={}, promptBlockReason={})",
                        activity.getId(), finishReason, root.path("promptFeedback").path("blockReason").asText("none"));
                return null;
            }

            JsonNode json;
            try {
                json = mapper.readTree(extractJson(text));
            } catch (JsonProcessingException e) {
                log.error("Gemini did not return valid JSON for activity {} (finishReason={}): {} | start of answer: {}",
                        activity.getId(), finishReason, e.getOriginalMessage(), abbreviate(text, 300));
                return null;
            }

            String analysis = extractAnalysis(json.path("analysis"));
            if (analysis.isBlank()) {
                log.error("Gemini's JSON for activity {} has no 'analysis' text; fields present: {}",
                        activity.getId(), fieldNames(json));
                return null;
            }

            List<String> performanceInsights = extractList(json.path("performanceInsights"));
            List<String> improvements = extractList(json.path("improvements"));
            List<String> nextSteps = extractList(json.path("nextSteps"));
            List<String> trainingRecommendations = extractList(json.path("trainingRecommendations"));
            List<String> recovery = extractList(json.path("recovery"));
            List<String> nutrition = extractList(json.path("nutrition"));
            List<String> safetyGuidelines = extractList(json.path("safetyGuidelines"));
            String summary = json.path("summary").asText("").trim();

            if (improvements.isEmpty() && nextSteps.isEmpty() && trainingRecommendations.isEmpty()) {
                log.error("Gemini's JSON for activity {} has an analysis but none of improvements/nextSteps/trainingRecommendations; fields present: {}",
                        activity.getId(), fieldNames(json));
                return null;
            }

            log.info("Parsed AI recommendation for activity {}: analysis={} chars, insights={}, improvements={}, nextSteps={}, "
                            + "training={}, recovery={}, nutrition={}, safety={}, summary={} chars",
                    activity.getId(), analysis.length(), performanceInsights.size(), improvements.size(), nextSteps.size(),
                    trainingRecommendations.size(), recovery.size(), nutrition.size(), safetyGuidelines.size(), summary.length());

            return Recommendation.builder()
                    .activityId(activity.getId())
                    .userId(activity.getUserId())
                    .activityType(activity.getType())
                    .recommendation(analysis)
                    .performanceInsights(performanceInsights)
                    .improvements(improvements)
                    .nextSteps(nextSteps)
                    .suggestions(trainingRecommendations)
                    .recovery(recovery)
                    .nutrition(nutrition)
                    .safety(safetyGuidelines)
                    .summary(summary)
                    .aiGenerated(true)
                    .createdAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("Could not read Gemini's response for activity {}: {}: {}",
                    activity.getId(), e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** Text of the first candidate; parts that only carry the model's "thoughts" are ignored. */
    private String extractText(JsonNode root) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false) && part.has("text")) {
                text.append(part.path("text").asText());
            }
        }
        return text.toString();
    }

    /** Gemini sometimes wraps JSON in ```json fences; keep only the outermost {...}. */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }

    private Recommendation createUnavailableRecommendation(Activity activity, String failureReason, boolean configurationProblem) {
        return Recommendation.builder()
                .activityId(activity.getId())
                .userId(activity.getUserId())
                .activityType(activity.getType())
                .recommendation(configurationProblem ? CONFIGURATION_PROBLEM_MESSAGE : UNAVAILABLE_MESSAGE)
                .failureReason(failureReason)
                .aiGenerated(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    /** "analysis" is a multi-paragraph string; an array of paragraphs or the older object form (overall/pace/...) is also accepted. */
    private String extractAnalysis(JsonNode analysisNode) {
        if (analysisNode.isTextual()) {
            return analysisNode.asText().trim();
        }
        if (analysisNode.isArray()) {
            return String.join("\n\n", extractList(analysisNode));
        }
        StringBuilder fullAnalysis = new StringBuilder();
        addAnalysisSection(fullAnalysis,analysisNode,"overall","Overall: ");
        addAnalysisSection(fullAnalysis,analysisNode,"pace","Pace: ");
        addAnalysisSection(fullAnalysis,analysisNode,"heartRate","Heart Rate: ");
        addAnalysisSection(fullAnalysis,analysisNode,"caloriesBurned","Calories: ");
        return fullAnalysis.toString().trim();
    }

    /** Items are strings ("Title: detail"); objects with title/detail style fields are flattened to the same form. */
    private List<String> extractList(JsonNode node) {
        List<String> items = new ArrayList<>();
        if (node.isTextual() && !node.asText().isBlank()) {
            items.add(node.asText().trim());
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String text = item.isTextual() ? item.asText().trim() : formatItem(item);
                if (!text.isBlank()) {
                    items.add(text);
                }
            }
        }
        return items;
    }

    private String formatItem(JsonNode item) {
        String title = firstText(item, "title", "area", "workout", "name", "topic");
        String detail = firstText(item, "detail", "details", "description", "recommendation", "explanation");
        return title.isEmpty() ? detail : (detail.isEmpty() ? title : title + ": " + detail);
    }

    private String firstText(JsonNode item, String... keys) {
        for (String key : keys) {
            String value = item.path(key).asText("").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private void addAnalysisSection(StringBuilder fullAnalysis, JsonNode analysisNode, String key, String prefix){
        if(!analysisNode.path(key).isMissingNode()){
            fullAnalysis.append(prefix)
                    .append(analysisNode.path(key).asText())
                    .append("\n\n");
        }
    }

    private List<String> fieldNames(JsonNode json) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = json.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    private String abbreviate(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }

    private String createPromptForActivity(Activity activity) {
        Integer duration = activity.getDuration();
        Integer calories = activity.getCaloriesBurned();
        String caloriesPerMinute = (duration != null && duration > 0 && calories != null)
                ? String.format(Locale.ROOT, "%.1f kcal per minute", calories / (double) duration)
                : "not available";

        return PROMPT_TEMPLATE.formatted(
                activity.getType() == null ? "not recorded" : activity.getType(),
                duration == null ? "not recorded" : duration + " minutes",
                calories == null ? "not recorded" : calories + " kcal",
                caloriesPerMinute,
                activity.getStartTime() == null ? "not recorded" : activity.getStartTime().toString(),
                describeMetrics(activity),
                focusFor(activity.getType())
        );
    }

    private String describeMetrics(Activity activity) {
        Map<String, Object> metrics = activity.getAdditionalMetrics();
        if (metrics == null || metrics.isEmpty()) {
            return "none recorded (for example no distance, pace, heart rate or step count was logged)";
        }
        try {
            return mapper.writeValueAsString(metrics);
        } catch (JsonProcessingException e) {
            return metrics.toString();
        }
    }

    /** Tells the model what matters for this kind of activity; the advice itself is still generated by the model. */
    private String focusFor(String type) {
        String normalized = type == null ? "" : type.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "WALKING" -> "Walking duration and consistency; pace and intensity (only if recorded, otherwise infer cautiously from calories per minute); progression of duration and effort; endurance; joint-friendly load; footwear; recovery; hydration; nutrition.";
            case "RUNNING" -> "Pace (only if recorded); endurance; progression and sensible weekly build-up; warm-up and cool-down; recovery; hydration; nutrition.";
            case "CYCLING" -> "Duration and endurance; cadence and intensity (only if recorded); progression; hydration; recovery; nutrition.";
            case "SWIMMING" -> "Session duration and endurance; technique and pacing; shoulder care; progression; recovery; hydration (even in the water); nutrition.";
            case "WEIGHT_TRAINING" -> "Training volume; progressive overload; technique; rest between sets and between sessions; recovery; protein and general nutrition. Sets, reps and weights are not known unless listed in the data above.";
            case "YOGA" -> "Session length; mobility and flexibility; breathing and mindfulness; consistency; recovery; hydration.";
            case "HIIT" -> "Work-to-rest structure (only if recorded); intensity management; progression; recovery needs, since HIIT is demanding; hydration; nutrition.";
            case "CARDIO" -> "Aerobic endurance; intensity; progression; recovery; hydration; nutrition.";
            case "STRETCHING" -> "Mobility and flexibility; consistency; how it complements other training; recovery; hydration.";
            default -> "General effort and consistency; progression; recovery; hydration; nutrition; safety.";
        };
    }
}
