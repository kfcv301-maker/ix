package com.admin.common.service;

import java.net.*;
import java.util.*;

/** Numeric address checks only: never resolve user input while deciding trust. */
public final class AirportAddressPolicy {
    private AirportAddressPolicy() { }
    public static InetAddress literal(String value) {
        if (value == null || value.length()>45 || value.contains("%")) return null;
        try {
            if (value.contains(":")) {
                if (!value.matches("[0-9a-fA-F:]+")) return null;
            } else {
                if (!value.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return null;
                for (String part:value.split("\\.")) if (Integer.parseInt(part)>255) return null;
            }
            return InetAddress.getByName(value);
        } catch (Exception invalid) { return null; }
    }
    public static boolean publicV4(String value) {
        if (value==null || value.contains(":")) return false;
        InetAddress address=literal(value);
        return address instanceof Inet4Address && publicAddress(address);
    }
    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b=address.getAddress();
        if (b.length==4) {
            int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            return a!=0 && a!=10 && a!=127 && a<224
                    && !(a==100 && c>=64 && c<=127) && !(a==169 && c==254)
                    && !(a==172 && c>=16 && c<=31) && !(a==192 && (c==168 || c==0 || (c==88 && d==99)))
                    && !(a==198 && (c==18 || c==19 || (c==51 && d==100))) && !(a==203 && c==0 && d==113);
        }
        // Only ordinary global unicast; exclude transition/documentation/reserved ranges.
        return (b[0]&0xe0)==0x20 && !inCidr(address,"2001::/23") && !inCidr(address,"2001:db8::/32")
                && !inCidr(address,"2002::/16") && !inCidr(address,"3fff::/20");
    }
    public static boolean inCidr(InetAddress address,String cidr) {
        try {
            String[] parts=cidr.trim().split("/",-1);
            InetAddress network=literal(parts[0]);
            if (network==null || parts.length>2) return false;
            byte[] a=address.getAddress(),n=network.getAddress();
            int bits=parts.length==1 ? n.length*8 : Integer.parseInt(parts[1]);
            if (a.length!=n.length || bits<0 || bits>n.length*8) return false;
            for (int i=0;i<bits;i++) if ((a[i/8]&(1<<(7-i%8)))!=(n[i/8]&(1<<(7-i%8)))) return false;
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
    public static String endpoint(String value) {
        return endpoint(value,"/api/v1/node-address-sync/report");
    }
    public static String origin(String value) {
        if (value==null) throw new IllegalArgumentException("请输入机场 HTTPS 地址");
        String origin=value.trim().replaceAll("/+$", "");
        return endpoint(origin+"/api/v1/node-address-sync/report").replace("/api/v1/node-address-sync/report", "");
    }
    public static String endpoint(String value,String path) {
        try {
            URI uri=URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getHost().length()>253
                    || !uri.getHost().matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                    || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                    || (uri.getPort()!=-1 && uri.getPort()!=443)
                    || !path.equals(uri.getRawPath())) throw new IllegalArgumentException();
            return "https://"+uri.getHost().toLowerCase(Locale.ROOT)+path;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("请输入 HTTPS 接收地址，路径为 /api/v1/node-address-sync/report，端口为 443");
        }
    }
}
