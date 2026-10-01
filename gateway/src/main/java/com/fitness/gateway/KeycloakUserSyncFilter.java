package com.fitness.gateway;

import com.fitness.gateway.User.RegisterRequest;
import com.fitness.gateway.User.UserService;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Slf4j
@RequiredArgsConstructor
public class KeycloakUserSyncFilter implements WebFilter {
    private final UserService userService;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain)
    {
        String token = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (token == null) {
            // preflight / actuator / anything unauthenticated: nothing to sync
            return chain.filter(exchange);
        }

        RegisterRequest registerRequest = getUserDetails(token);
        // the verified JWT subject wins over a client supplied X-User-ID header
        String userId = registerRequest != null && registerRequest.getKeycloakId() != null
                ? registerRequest.getKeycloakId()
                : exchange.getRequest().getHeaders().getFirst("X-User-ID");

        if (userId == null) {
            return chain.filter(exchange);
        }

        String finalUserId = userId;
        return userService.validateUser(finalUserId)
                .flatMap(exist -> {
                    if (!exist && registerRequest != null) {
                        return userService.registerUser(registerRequest).then();
                    }
                    log.info("User already exists , Skipping sync");
                    return Mono.<Void>empty();
                })
                .then(Mono.defer(() -> {
                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header("X-User-ID", finalUserId)
                            .build();
                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                }));
    }

    private RegisterRequest getUserDetails(String token) {
        try{
            String tokenWithoutBearer = token.replace("Bearer ","").trim();
            SignedJWT signedJWT = SignedJWT.parse(tokenWithoutBearer);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            RegisterRequest registerRequest = new RegisterRequest();
            registerRequest.setKeycloakId(claims.getStringClaim("sub"));
            registerRequest.setEmail(claims.getStringClaim("email"));
            registerRequest.setPassword("dummy@1232");
            registerRequest.setFirstName(claims.getStringClaim("given_name"));
            registerRequest.setLastName(claims.getStringClaim("family_name"));
            return registerRequest;
        } catch (Exception e) {
            log.warn("Could not parse Authorization token: {}", e.getMessage());
            return null;
        }
    }
}
