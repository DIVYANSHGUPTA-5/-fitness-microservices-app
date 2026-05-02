package com.fitness.activityservice.config;


import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    @LoadBalanced //so instead of hardcoding url of other microservice just write its name , so by using microservice name on
    //eureka server this service can access other service irrespective of whether ip of that server change or not
    public WebClient.Builder webClientBuilder()
    {
        return WebClient.builder();
    }

    @Bean
    public WebClient userServiceWebClient(WebClient.Builder webClientBuilder)
    {
        return webClientBuilder
                .baseUrl("http://USER-SERVICE")
                .build();
    }
}
