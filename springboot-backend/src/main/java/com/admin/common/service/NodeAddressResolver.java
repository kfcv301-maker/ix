package com.admin.common.service;

import com.admin.entity.Node;
import org.springframework.stereotype.Component;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.Attributes;
import javax.naming.directory.Attribute;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import java.net.IDN;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;

@Component
public class NodeAddressResolver {
    public static String target(Node node) {
        String value = Objects.equals(node.getDdnsEnabled(), 1) && node.getDdnsRecordName() != null
                && !node.getDdnsRecordName().isBlank() ? node.getDdnsRecordName()
                : node.getServerIp() != null && !node.getServerIp().isBlank() ? node.getServerIp() : node.getIp();
        if (value == null) return "";
        return value.trim();
    }

    static String literal(String value) {
        if (value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            String[] parts = value.split("\\.");
            for (int i=0;i<parts.length;i++) {
                int n = Integer.parseInt(parts[i]); if (n>255) return null;
                parts[i] = Integer.toString(n);
            }
            return String.join(".", parts);
        }
        if (value.contains(":") && value.matches("[0-9a-fA-F:.]+")) {
            try { return InetAddress.getByName(value).getHostAddress(); }
            catch (Exception ignored) { return null; }
        }
        return null;
    }

    public NodeIpObservation.Result resolve(String target) {
        String value = target;
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1,value.length()-1);
        String ip = literal(value);
        if (ip != null) return new NodeIpObservation.Result("ok", "configured", List.of(ip));
        try {
            value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES);
            if (value.endsWith(".")) value = value.substring(0,value.length()-1);
            if (value.isEmpty() || value.length()>253 || !value.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                    || value.matches("[0-9.]+")) return failure("invalid_target");
        } catch (IllegalArgumentException invalid) { return failure("invalid_target"); }
        Hashtable<String,String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "1500");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext context = null;
        try {
            context = openContext(environment);
            List<String> addresses = new ArrayList<>();
            for (String family : List.of("A", "AAAA")) {
                // JNDI combines multiple record types into ANY. Modern DNS servers may
                // legitimately return no address records to ANY, so query each family.
                Attributes records = context.getAttributes(value + ".", new String[]{family});
                Attribute attribute = records.get(family);
                if (attribute == null) continue;
                NamingEnumeration<?> entries = attribute.getAll();
                try {
                    while (entries.hasMore()) {
                        String normalized = literal(String.valueOf(entries.next()));
                        if (normalized == null || addresses.size() >= 64) return failure("invalid_answer");
                        addresses.add(normalized);
                    }
                } finally { entries.close(); }
            }
            return new NodeIpObservation.Result(addresses.isEmpty() ? "no_records" : "ok", "dns", addresses);
        } catch (Exception unavailable) { return failure("resolve_failed"); }
        finally { if (context != null) try { context.close(); } catch (Exception ignored) {} }
    }

    InitialDirContext openContext(Hashtable<String,String> environment) throws NamingException {
        return new InitialDirContext(environment);
    }

    private NodeIpObservation.Result failure(String status) { return new NodeIpObservation.Result(status,"dns",List.of()); }
}
