package com.istad.stadoor.tunnelagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TargetResponse(
    String targetId,
    String tunnelId,
    String publicUrl,
    String ipAddress,
    String key,
    int localPort,
    LocalDateTime createdAt
) {}
