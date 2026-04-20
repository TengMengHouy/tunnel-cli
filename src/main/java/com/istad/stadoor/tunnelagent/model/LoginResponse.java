package com.istad.stadoor.tunnelagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginResponse(UUID userId, String token) {}