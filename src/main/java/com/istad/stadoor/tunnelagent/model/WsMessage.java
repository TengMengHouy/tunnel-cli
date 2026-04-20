package com.istad.stadoor.tunnelagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WsMessage {
    private String type;
    private String requestId;
    private Map<String, Object> payload;

    public WsMessage() {}
    public WsMessage(String t, String r, Map<String, Object> p) {
        type = t; requestId = r; payload = p;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
}
