package com.istad.stadoor.tunnelagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private String serverUrl = "https://tunnel-production-0c37.up.railway.app/";
    private int heartbeatInterval = 30;
    private int connectionTimeout = 5000;
    private int readTimeout = 10000;

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String v) { serverUrl = v; }

    public int getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(int v) { heartbeatInterval = v; }

    public int getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(int v) { connectionTimeout = v; }

    public int getReadTimeout() { return readTimeout; }
    public void setReadTimeout(int v) { readTimeout = v; }
}
