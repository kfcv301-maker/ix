package com.admin.common.utils;

import com.admin.entity.VpsHost;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Prevents VPS hosting from being used as an SSRF pivot into the panel's own
 * network. A hostname is resolved both when it is saved and immediately before
 * SSH connects, so a DNS record cannot be changed to an internal address after
 * validation.
 */
@Component
public class VpsSshTargetPolicy {

    @Value("${vps.security.allow-private-admin-targets:false}")
    private boolean allowPrivateAdminTargets;

    /**
     * Normalizes a user-supplied SSH hostname and proves that every current DNS
     * answer is safe. Administrator inventory can opt into private targets only
     * through an explicit deployment setting; ordinary user submissions never
     * receive that exception.
     */
    public String normalizeForStorage(String value, boolean administratorInventory) {
        String host = normalizeSyntax(value);
        resolveAndValidate(host, canUsePrivateTarget(administratorInventory));
        return host;
    }

    /**
     * Re-resolves the hostname at the point of use and returns a concrete IP.
     * Connecting to this IP rather than the original hostname closes the DNS
     * rebinding window between validation and SSHJ's socket creation.
     */
    public String resolveForConnection(VpsHost host) {
        if (host == null) {
            throw new IllegalArgumentException("VPS SSH 配置不完整");
        }
        String normalizedHost = normalizeSyntax(host.getHost());
        InetAddress[] addresses = resolveAndValidate(normalizedHost,
                canUsePrivateTarget("ADMIN".equals(host.getOrigin())));
        return addresses[0].getHostAddress();
    }

    private boolean canUsePrivateTarget(boolean administratorInventory) {
        return administratorInventory && allowPrivateAdminTargets;
    }

    private String normalizeSyntax(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("SSH 地址不能为空");
        }
        String host = value.trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        String lower = host.toLowerCase();
        if (host.isEmpty() || host.length() > 255 || host.matches(".*\\s+.*")
                || host.contains("://") || host.contains("/") || host.indexOf('\\') >= 0
                || host.contains("@") || host.contains("?") || host.contains("#") || host.contains("%")
                || "localhost".equals(lower) || "localhost.".equals(lower)) {
            throw new IllegalArgumentException("SSH 地址格式无效");
        }
        return host;
    }

    private InetAddress[] resolveAndValidate(String host, boolean privateTargetAllowed) {
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("SSH 地址无法解析");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("SSH 地址无法解析");
        }
        for (InetAddress address : addresses) {
            if (!isAllowed(address, privateTargetAllowed)) {
                throw new IllegalArgumentException("SSH 地址必须指向可访问的公网 IP");
            }
        }
        return addresses;
    }

    private boolean isAllowed(InetAddress address, boolean privateTargetAllowed) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet4Address) {
            return isAllowedIpv4(address.getAddress(), privateTargetAllowed);
        }
        if (address instanceof Inet6Address) {
            return isAllowedIpv6((Inet6Address) address, privateTargetAllowed);
        }
        return false;
    }

    private boolean isAllowedIpv4(byte[] bytes, boolean privateTargetAllowed) {
        if (bytes == null || bytes.length != 4) {
            return false;
        }
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        int third = unsigned(bytes[2]);

        // Unspecified/reserved, loopback, link-local, multicast and future use.
        if (first == 0 || first == 127 || first >= 224 || (first == 169 && second == 254)) {
            return false;
        }
        // Documentation, benchmarking and other non-public special ranges.
        if ((first == 192 && second == 0 && third == 0)
                || (first == 192 && second == 88 && third == 99)
                || (first == 192 && second == 0 && third == 2)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)) {
            return false;
        }
        if (privateTargetAllowed) {
            return true;
        }
        return !((first == 10)
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168));
    }

    private boolean isAllowedIpv6(Inet6Address address, boolean privateTargetAllowed) {
        byte[] bytes = address.getAddress();
        if (bytes == null || bytes.length != 16 || address.isSiteLocalAddress()) {
            return false;
        }
        // IPv4-mapped and IPv4-compatible addresses inherit the IPv4 policy.
        if (isIpv4Mapped(bytes) || address.isIPv4CompatibleAddress()) {
            byte[] ipv4 = new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
            return isAllowedIpv4(ipv4, privateTargetAllowed);
        }
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        // Unique-local fc00::/7 and deprecated site-local space are not public.
        if ((first & 0xfe) == 0xfc || (first == 0xfe && (second & 0xc0) == 0xc0)) {
            return privateTargetAllowed && (first & 0xfe) == 0xfc;
        }
        // Transition prefixes can encode an IPv4 destination and otherwise
        // bypass the IPv4 private/link-local checks above.
        if ((first == 0x20 && second == 0x02)
                || (first == 0x00 && second == 0x64 && unsigned(bytes[2]) == 0xff
                && unsigned(bytes[3]) == 0x9b
                && ((unsigned(bytes[4]) == 0x00 && unsigned(bytes[5]) == 0x00)
                || (unsigned(bytes[4]) == 0x00 && unsigned(bytes[5]) == 0x01)))) {
            return false;
        }
        // Documentation (2001:db8::/32) and discard-only (100::/64).
        if ((first == 0x20 && second == 0x01 && unsigned(bytes[2]) == 0x0d && unsigned(bytes[3]) == 0xb8)
                || (first == 0x01 && second == 0x00 && unsigned(bytes[2]) == 0x00 && unsigned(bytes[3]) == 0x00
                && unsigned(bytes[4]) == 0x00 && unsigned(bytes[5]) == 0x00 && unsigned(bytes[6]) == 0x00
                && unsigned(bytes[7]) == 0x00)) {
            return false;
        }
        return true;
    }

    private boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }
}
