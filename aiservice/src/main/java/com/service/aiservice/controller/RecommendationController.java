package com.service.aiservice.controller;
import com.service.aiservice.model.Recommendation;
import com.service.aiservice.service.RecommendationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {
    @Autowired
    private RecommendationService recommendationService;

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Recommendation>> getUserRecommendation(@PathVariable String userId)
    {
        return ResponseEntity.ok(recommendationService.getUserRecommendation(userId));
    }
    @GetMapping("/activity/{ActivityId}")
    public ResponseEntity<Recommendation> getActivityRecommendation(@PathVariable String ActivityId)
    {
        return ResponseEntity.ok(recommendationService.getActivityRecommendation(ActivityId));
    }
}
