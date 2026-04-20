package com.istad.stadoor.tunnelagent.service;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Getter
@Component
public class AgentSessionHolder {

    private UUID userId;
    private String token;
    private UUID clientId;
    private boolean registered;

    public boolean isLoggedIn() {
        return token != null && userId != null;
    }

    public void login(UUID userId, String token) {
        this.userId = userId;
        this.token = token;
    }

    public void logout() {
        this.userId = null;
        this.token = null;
        this.clientId = null;
        this.registered = false;
    }

    public boolean isRegistered() {
        return registered && clientId != null;
    }

    public void register(UUID clientId) {
        this.clientId = clientId;
        this.registered = true;
    }

    public void unregister() {
        this.clientId = null;
        this.registered = false;
    }

}