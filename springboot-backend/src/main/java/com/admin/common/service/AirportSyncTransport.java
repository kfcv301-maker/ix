package com.admin.common.service;

import com.alibaba.fastjson2.JSONObject;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
public class AirportSyncTransport {
    private final java.util.concurrent.ThreadPoolExecutor dns=new java.util.concurrent.ThreadPoolExecutor(2,2,0,
            java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"airport-dns");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    @jakarta.annotation.PreDestroy public void close() { dns.shutdownNow(); }
    protected InetAddress[] resolve(String host) throws UnknownHostException {
        java.util.concurrent.Future<InetAddress[]> request=null;
        try { request=dns.submit(()->InetAddress.getAllByName(host));return request.get(2,java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception unavailable) { if(request!=null)request.cancel(true);throw new UnknownHostException("Destination resolution unavailable"); }
    }
    public String send(String endpoint,String binding,String token,String ip,long observedAt) {
        try {
            URI uri=URI.create(AirportAddressPolicy.endpoint(endpoint));
            InetAddress[] addresses=resolve(uri.getHost());
            if (addresses.length==0 || addresses.length>16) return "blocked_destination";
            for (InetAddress address:addresses) if (!AirportAddressPolicy.publicAddress(address)) return "blocked_destination";
            // Resolution is pinned for this one request; the HTTP layer cannot re-resolve to private space.
            DnsResolver pinned=new DnsResolver() {
                public InetAddress[] resolve(String host) throws UnknownHostException {
                    if (!uri.getHost().equalsIgnoreCase(host)) throw new UnknownHostException();
                    return addresses.clone();
                }
                public String resolveCanonicalHostname(String host) throws UnknownHostException {
                    if (!uri.getHost().equalsIgnoreCase(host)) throw new UnknownHostException();
                    return uri.getHost();
                }
            };
            var manager=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
                    .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(2))
                            .setSocketTimeout(Timeout.ofSeconds(3)).build()).build();
            try (var client=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                    .disableAutomaticRetries().disableCookieManagement().disableContentCompression()
                    .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(2))
                            .setResponseTimeout(Timeout.ofSeconds(3)).build()).build()) {
                HttpPost request=new HttpPost(uri);
                request.setHeader("Authorization","Bearer "+token);
                request.setEntity(new StringEntity(JSONObject.of("binding_id",binding,"ip",ip,"observed_at",observedAt).toJSONString(),ContentType.APPLICATION_JSON));
                // Bound total time as well as socket inactivity. Cancels slow-drip responses.
                var deadline=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"airport-request-deadline");t.setDaemon(true);return t;});
                var cancel=deadline.schedule(request::cancel,8,java.util.concurrent.TimeUnit.SECONDS);
                try {
                    return client.execute(request,response->{
                        int code=response.getCode();
                        if (Set.of(401,403,409,422).contains(code)) return "blocked_"+code;
                        if (code>=300 && code<400) return "blocked_redirect";
                        if (code!=200) return "retry_http";
                        if (response.getEntity()==null) return "retry_receipt";
                        try (var stream=response.getEntity().getContent()) {
                            byte[] body=stream.readNBytes(8193);
                            if (body.length>8192) return "retry_receipt";
                            return receipt(new String(body,StandardCharsets.UTF_8),ip);
                        }
                    });
                } finally { cancel.cancel(false);deadline.shutdownNow(); }
            }
        } catch (Exception failure) { return "retry_transport"; } // Never log token, remote response, or exception text.
    }
    static String receipt(String body,String ip) {
        try {
            JSONObject json=JSONObject.parseObject(body),data=json.getJSONObject("data");
            if (json.containsKey("code") && json.getIntValue("code")!=0) return "retry_receipt";
            return data!=null && Set.of("updated","unchanged","duplicate").contains(data.getString("status"))
                    && ip.equals(data.getString("ip")) ? "synced" : "retry_receipt";
        } catch (RuntimeException invalid) { return "retry_receipt"; }
    }
}
