package com.admin.common.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TrafficPoolQuota {
    private static final String OD_API="https://api.odyun.top/api";
    private static final long STALE_MS=Duration.ofMinutes(15).toMillis();
    private static final BigDecimal GB=BigDecimal.valueOf(1_000_000_000L);
    private final Path config;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile Map<String,Quota> quotas=Map.of();

    public record Quota(long totalBytes, long usedBytes, long remainingBytes, boolean exhausted, long syncedAt) {
        public boolean stale() { return System.currentTimeMillis()-syncedAt>STALE_MS; }
    }

    public TrafficPoolQuota(@Value("${traffic-pool.od.credentials-file:/app/config/traffic-pool-od.json}") String path) {
        config=Path.of(path);
    }

    Quota quota(String ip) { return quotas.get(ip); }

    @Scheduled(initialDelay=6000,fixedDelay=180000,scheduler="trafficPoolQuotaScheduler")
    void refresh() {
        try {
            if (!Files.isRegularFile(config) || Files.size(config)>16_384) return;
            var credentials=JSON.parseObject(Files.readString(config));
            if(credentials.isEmpty() || credentials.size()>16) return;
            var fresh=new HashMap<String,Quota>();
            var ambiguous=new java.util.HashSet<String>();
            boolean complete=true;
            long now=System.currentTimeMillis();
            for(var account:credentials.entrySet()) {
                if (!(account.getValue() instanceof String password) || password.isBlank()) { complete=false; continue; }
                try {
                    for(var instance:instances(account.getKey(),password)) {
                        var ip=TrafficPoolDns.normalize(instance.getString("public_ip"));
                        Quota value=readQuota(instance,now);
                        if(ip.isEmpty() || value==null) continue;
                        if(fresh.putIfAbsent(ip,value)!=null) ambiguous.add(ip);
                    }
                } catch(IOException | RuntimeException unavailable) {
                    complete=false;
                }
            }
            if(!complete) quotas.forEach(fresh::putIfAbsent);
            ambiguous.forEach(fresh::remove);
            quotas=Map.copyOf(fresh);
        } catch(InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch(IOException | RuntimeException unavailable) {
            // Keep the previous sample and expose its timestamp. Do not log provider credentials or payloads.
        }
    }

    static Quota readQuota(JSONObject instance,long now) {
        BigDecimal allowance=instance.getBigDecimal("max_traffic");
        BigDecimal used=instance.getBigDecimal("used_traffic");
        BigDecimal extra=instance.getBigDecimal("extra_traffic_gb");
        if(allowance==null || used==null || allowance.signum()<0 || used.signum()<0
                || extra!=null && extra.signum()<0) return null;
        long total=allowance.add(extra==null?BigDecimal.ZERO:extra).multiply(GB).longValueExact();
        long usedBytes=used.multiply(GB).longValueExact();
        boolean exhausted=instance.getBooleanValue("traffic_exhausted") || usedBytes>=total;
        return new Quota(total,usedBytes,exhausted?0:total-usedBytes,exhausted,now);
    }

    private List<JSONObject> instances(String user,String password) throws IOException,InterruptedException {
        var login=request("/auth/login",JSONObject.of("username",user,"password",password),null);
        String token=login.getString("access_token");
        if(token==null || token.isBlank() || token.length()>4096) throw new IOException("OD authentication failed");
        var result=new ArrayList<JSONObject>();
        for(int skip=0;skip<1000;skip+=100) {
            var page=request("/ecs?skip="+skip+"&limit=100",null,token);
            var list=page.getJSONArray("list");
            if(list==null || list.size()>100) throw new IOException("Invalid OD page");
            for(int i=0;i<list.size();i++) result.add(list.getJSONObject(i));
            Integer total=page.getInteger("total");
            if(total==null || total<0) throw new IOException("Invalid OD total");
            if(result.size()>=total) return result;
            if(list.isEmpty()) break;
        }
        throw new IOException("Incomplete OD inventory");
    }

    private JSONObject request(String path,JSONObject body,String token) throws IOException,InterruptedException {
        var builder=HttpRequest.newBuilder(URI.create(OD_API+path)).timeout(Duration.ofSeconds(8))
                .header("Accept","application/json").header("Origin","https://console.odyun.top")
                .header("Referer","https://console.odyun.top/").header("User-Agent","Mozilla/5.0");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(body!=null) builder.header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var stream=response.body()) {
            if(response.statusCode()!=200) throw new IOException("OD request failed");
            byte[] bytes=stream.readNBytes(1_000_001);
            if(bytes.length>1_000_000) throw new IOException("OD response too large");
            var root=JSON.parseObject(bytes);
            var data=root.getJSONObject("data");
            if(root.getIntValue("code")!=200 || data==null) throw new IOException("Invalid OD response");
            return data;
        }
    }
}
