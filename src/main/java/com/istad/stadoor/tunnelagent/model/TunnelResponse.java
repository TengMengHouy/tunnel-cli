package com.istad.stadoor.tunnelagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TunnelResponse(
    String tunnelId,
    String userId,
    String basePath,
    boolean active,
    LocalDateTime createdAt
) {}
