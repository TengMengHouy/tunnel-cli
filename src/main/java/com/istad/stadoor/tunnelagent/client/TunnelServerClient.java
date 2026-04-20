package com.istad.stadoor.tunnelagent.client;

import com.istad.stadoor.tunnelagent.model.*;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TunnelServerClient {

    private final WebClient webClient;

    public TunnelServerClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<LoginResponse> login(String token) {
        return webClient.post()
                .uri("/api/auth/login")
                .bodyValue(Map.of("token", token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r ->
                        r.bodyToMono(Map.class)
                                .flatMap(b -> Mono.error(new RuntimeException(
                                        b.getOrDefault("error", "Login failed").toString()
                                )))
                )
                .bodyToMono(LoginResponse.class);
    }

    public Mono<List<TunnelResponse>> listTunnels(UUID userId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/tunnels")
                        .queryParam("userId", userId)
                        .build()
                )
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToFlux(TunnelResponse.class)
                .collectList();
    }

    public Mono<TunnelResponse> getTunnel(UUID tunnelId) {
        return webClient.get()
                .uri("/api/tunnels/{id}", tunnelId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(TunnelResponse.class);
    }

    public Mono<Void> deactivateTunnel(UUID tunnelId) {
        return webClient.post()
                .uri("/api/tunnels/{id}/deactivate", tunnelId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(Void.class);
    }

//    public Mono<AddTargetResponse> addTarget(UUID tunnelId, int port) {
//        return webClient.post()
//                .uri("/api/tunnels/{id}/targets", tunnelId)
//                .bodyValue(Map.of("localPort", port))
//                .retrieve()
//                .onStatus(HttpStatusCode::isError, this::handleError)
//                .bodyToMono(AddTargetResponse.class);
//    }

    public Mono<List<TargetResponse>> listTargets(UUID tunnelId) {
        return webClient.get()
                .uri("/api/tunnels/{id}/targets", tunnelId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToFlux(TargetResponse.class)
                .collectList();
    }

    @SuppressWarnings("unchecked")
    private Mono<? extends Throwable> handleError(
            org.springframework.web.reactive.function.client.ClientResponse r) {
        return r.bodyToMono(Map.class)
                .flatMap(b -> Mono.error(new RuntimeException(
                        b.getOrDefault("error", "Server error " + r.statusCode().value()).toString()
                )));
    }
    public Mono<TargetResponse> addTarget(UUID tunnelId, String ipAddress, int port) {
        return webClient.post()
                .uri("/api/tunnels/{id}/targets", tunnelId)
                .bodyValue(Map.of(
                        "ipAddress", ipAddress,
                        "localPort", port
                ))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleError)
                .bodyToMono(TargetResponse.class);
    }
}