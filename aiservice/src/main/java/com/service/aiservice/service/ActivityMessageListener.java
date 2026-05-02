package com.service.aiservice.service;

import com.service.aiservice.model.Activity;
import com.service.aiservice.model.Recommendation;
import com.service.aiservice.repository.RecommendationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ActivityMessageListener {

    private final ActivityAIService aiService;
    private final RecommendationRepository recommendationRepository;
    @RabbitListener(queues = "activity.queue")
    public void processActivity(Activity activity)
    {
        log.info("Received activity for processing:{}",activity.getId());
        //log.info("Generated Recommendation:{}",aiService.generateRecommendation(activity));
        Recommendation recommendation = aiService.generateRecommendation(activity);
        recommendationRepository.save(recommendation);
    }
}
