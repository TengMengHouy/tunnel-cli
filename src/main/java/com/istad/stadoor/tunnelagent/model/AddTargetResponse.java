package com.istad.stadoor.tunnelagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AddTargetResponse(
        UUID targetId,
        String publicUrl,
        String key,
        String ipAddress,
        int localPort
) {}