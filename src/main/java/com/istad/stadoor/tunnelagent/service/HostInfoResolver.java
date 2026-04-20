package com.istad.stadoor.tunnelagent.service;

import org.springframework.stereotype.Component;
import java.net.InetAddress;

@Component
public class HostInfoResolver {

    public String getHostName() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }

    public String getIpAddress() {
        try { return InetAddress.getLocalHost().getHostAddress(); }
        catch (Exception e) { return "127.0.0.1"; }
    }

    public String getOsType() {
        return System.getProperty("os.name", "unknown");
    }

    public String getHostPort() {
        return "0";
    }
}
