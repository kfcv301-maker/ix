package com.admin.config;

import com.admin.common.service.AirportAddressPolicy;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.net.InetAddress;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** Read the original servlet request, before framework ForwardedHeaderFilter wrappers. */
@Configuration
public class AirportPeerSource {
    public static final String ATTRIBUTE=AirportPeerSource.class.getName()+".ipv4";
    private final String mode;
    private final List<String> trusted;
    public AirportPeerSource(@Value("${airport-sync.source-mode:off}") String mode,
                             @Value("${airport-sync.trusted-proxies:}") String trusted) {
        if (!Set.of("off","direct","trusted-proxy").contains(mode)) throw new IllegalArgumentException("Invalid airport IP source mode");
        this.mode=mode; this.trusted=Arrays.stream(trusted.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList();
    }
    public String resolve(String peer,String forwarded) {
        if ("off".equals(mode)) return null;
        if ("direct".equals(mode)) return AirportAddressPolicy.publicV4(peer) ? peer : null;
        if (!trusted(peer) || forwarded==null || forwarded.length()>2048) return null;
        String[] hops=forwarded.split(",",-1);
        if (hops.length>32) return null;
        String current=peer;
        for (int i=hops.length-1;i>=0 && trusted(current);i--) {
            current=hops[i].trim();
            if (AirportAddressPolicy.literal(current)==null) return null;
        }
        return !trusted(current) && AirportAddressPolicy.publicV4(current) ? current : null;
    }
    private boolean trusted(String value) {
        InetAddress address=AirportAddressPolicy.literal(value);
        return address!=null && trusted.stream().anyMatch(cidr->AirportAddressPolicy.inCidr(address,cidr));
    }
    public String resolveRequest(HttpServletRequest request) {
        ServletRequest original=request;
        int depth=0;
        while (original instanceof ServletRequestWrapper wrapper) {
            if (++depth>32 || wrapper.getRequest()==original) return null;
            original=wrapper.getRequest();
        }
        if (!(original instanceof HttpServletRequest raw)) return null;
        return resolve(raw.getRemoteAddr(),raw.getHeader("X-Forwarded-For"));
    }
}
