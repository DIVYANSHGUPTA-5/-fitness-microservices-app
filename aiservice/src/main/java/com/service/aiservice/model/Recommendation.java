package com.service.aiservice.model;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "recommendations")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Recommendation {
    @Id
    private String id;
    private String activityId;
    private String userId;
    private String activityType;
    private String recommendation;
    private List<String> improvements;
    private List<String> suggestions;
    private List<String> safety;
    // richer sections; null for recommendations stored before they existed
    private List<String> performanceInsights;
    private List<String> nextSteps;
    private List<String> recovery;
    private List<String> nutrition;
    private String summary;
    // false when Gemini could not produce an analysis and this is only the "temporarily unavailable" placeholder;
    // null for recommendations stored before this field existed
    private Boolean aiGenerated;
    // why no AI analysis exists (Gemini's own error, never the API key); only set on the "unavailable" placeholder
    private String failureReason;
    @CreatedDate
    private LocalDateTime createdAt;

}
