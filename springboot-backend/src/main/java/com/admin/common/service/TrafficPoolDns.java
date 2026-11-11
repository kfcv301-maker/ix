package com.admin.common.service;

import com.alibaba.fastjson2.*;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Fixed provider origin; tokens and remote response bodies never enter logs or browser responses. */
@Component
public class TrafficPoolDns {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Record(String id,String type,String name,String content,String comment,boolean proxied) { }
    JSONObject request(String method,String path,String token,JSONObject body) {
        try {
            var builder=HttpRequest.newBuilder(URI.create("https://api.cloudflare.com/client/v4"+path))
                    .timeout(Duration.ofSeconds(8)).header("Authorization","Bearer "+token).header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toJSONString()));
            var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>1_000_000) throw new IllegalStateException();
            var data=JSONObject.parseObject(response.body());
            if(!Boolean.TRUE.equals(data.getBoolean("success"))) throw new IllegalStateException();
            return data;
        } catch(Exception failed) { if(failed instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("DNS 服务暂时不可用，请核对令牌权限及连接"); }
    }
    static String path(String zone) {
        if(zone==null || !zone.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("请输入 32 位 Zone ID");
        return "/zones/"+zone+"/dns_records";
    }
    public String findZone(String domain, String token) {
        String suffix = domain;
        while (suffix.contains(".")) {
            var rows = request("GET", "/zones?status=active&name=" + suffix, token, null).getJSONArray("result");
            if (rows == null || rows.size() > 1) throw new IllegalStateException("无法唯一确定 Cloudflare 域名");
            if (rows.size() == 1) {
                var zone = rows.getJSONObject(0);
                if (!suffix.equals(zone.getString("name"))) throw new IllegalStateException("Cloudflare 域名回执不匹配");
                String id = zone.getString("id");
                path(id);
                return id;
            }
            suffix = suffix.substring(suffix.indexOf('.') + 1);
        }
        throw new IllegalArgumentException("令牌没有访问该域名的权限");
    }
    public List<Record> list(String zone,String domain,String token) {
        var result=new ArrayList<Record>();
        for(int page=1;page<=8;page++) {
            var reply=request("GET",path(zone)+"?name="+URLEncoder.encode(domain,java.nio.charset.StandardCharsets.UTF_8)+"&per_page=100&page="+page,token,null);
            var rows=reply.getJSONArray("result");if(rows==null)throw new IllegalStateException("DNS 回执无效");
            for(int i=0;i<rows.size();i++) {
                var r=rows.getJSONObject(i);
                if(!domain.equalsIgnoreCase(r.getString("name")))throw new IllegalStateException("DNS 域名回执不匹配");
                result.add(new Record(r.getString("id"),r.getString("type"),r.getString("name"),r.getString("content"),r.getString("comment"),r.getBooleanValue("proxied")));
            }
            if(rows.size()<100)return result;
        }
        throw new IllegalStateException("该域名记录过多，请人工核对");
    }
    public Record create(String zone,String domain,String ip,int ttl,String owner,String token) {
        var r=request("POST",path(zone),token,JSONObject.of("type",ip.contains(":")?"AAAA":"A","name",domain,"content",ip,"ttl",ttl,"proxied",false,"comment",owner)).getJSONObject("result");
        if(r==null || r.getString("id")==null || !r.getString("id").matches("[a-f0-9]{32}") || !(ip.contains(":")?"AAAA":"A").equals(r.getString("type"))
                || !ip.equals(normalize(r.getString("content"))) || !domain.equalsIgnoreCase(r.getString("name")) || !owner.equals(r.getString("comment")) || r.getBooleanValue("proxied")) throw new IllegalStateException("新增 DNS 记录未获正确回执");
        return new Record(r.getString("id"),r.getString("type"),domain,ip,owner,false);
    }
    public void delete(String zone,Record record,String domain,String owner,String token) {
        if(record.id()==null || !record.id().matches("[a-f0-9]{32}") || !domain.equalsIgnoreCase(record.name()) || !owner.equals(record.comment()) || !Set.of("A","AAAA").contains(record.type())) throw new IllegalStateException("拒绝删除未由此流量池管理的记录");
        // Re-read immediately before deletion: external edits revoke our ownership.
        var current=request("GET",path(zone)+"/"+record.id(),token,null).getJSONObject("result");
        if(current==null || !owner.equals(current.getString("comment")) || !domain.equalsIgnoreCase(current.getString("name"))
                || !record.type().equals(current.getString("type")) || !Objects.equals(normalize(record.content()),normalize(current.getString("content")))) throw new IllegalStateException("DNS 记录已被外部修改，停止删除");
        var receipt=request("DELETE",path(zone)+"/"+record.id(),token,null).getJSONObject("result");
        if(receipt==null || !record.id().equals(receipt.getString("id")))throw new IllegalStateException("DNS 删除回执不匹配");
    }
    public void adopt(String zone,Record record,String domain,String owner,String token) {
        if(record.id()==null || !record.id().matches("[a-f0-9]{32}") || !domain.equalsIgnoreCase(record.name()) || !Set.of("A","AAAA").contains(record.type()) || record.proxied()
                || record.comment()!=null && record.comment().startsWith("lunaris-pool/") && !owner.equals(record.comment()))throw new IllegalStateException("不能接管此记录");
        var current=request("GET",path(zone)+"/"+record.id(),token,null).getJSONObject("result");
        if(current==null || !domain.equalsIgnoreCase(current.getString("name")) || !record.type().equals(current.getString("type"))
                || !Objects.equals(normalize(record.content()),normalize(current.getString("content"))) || !Objects.equals(record.comment(),current.getString("comment")) || current.getBooleanValue("proxied"))throw new IllegalStateException("DNS 已变化，请重新预览");
        var reply=request("PATCH",path(zone)+"/"+record.id(),token,JSONObject.of("comment",owner)).getJSONObject("result");
        if(reply==null || !record.id().equals(reply.getString("id")) || !domain.equalsIgnoreCase(reply.getString("name")) || !owner.equals(reply.getString("comment")) || !Objects.equals(normalize(record.content()),normalize(reply.getString("content"))))throw new IllegalStateException("DNS 接管回执不匹配");
    }
    static String normalize(String ip) {
        var address=AirportAddressPolicy.literal(ip);return address==null?"":address.getHostAddress();
    }
    public static List<String> desired(Collection<TrafficPoolState.Member> members) {
        return members.stream().filter(m->m.enabled)
                .flatMap(m->m.health.entrySet().stream()).filter(e->e.getValue().eligible).map(Map.Entry::getKey).distinct().sorted().toList();
    }
    public List<Map<String,String>> plan(List<Record> records,String owner,List<String> desired) {
        if(records.stream().anyMatch(r->"CNAME".equals(r.type())))throw new IllegalStateException("入口域名已有 CNAME，请使用独立的流量池子域名");
        if(records.stream().anyMatch(r->Set.of("A","AAAA").contains(r.type()) && (!owner.equals(r.comment()) || r.proxied())))
            throw new IllegalStateException("该域名存在未托管或代理记录，请使用专用空子域名；不会覆盖或删除原记录");
        var changes=new ArrayList<Map<String,String>>();
        for(String ip:desired) if(records.stream().noneMatch(r->owner.equals(r.comment()) && (ip.contains(":")?"AAAA":"A").equals(r.type()) && ip.equals(normalize(r.content()))))changes.add(Map.of("action","add","ip",ip));
        for(var r:records) if(owner.equals(r.comment()) && Set.of("A","AAAA").contains(r.type()) && !desired.contains(normalize(r.content())))changes.add(Map.of("action","remove","ip",normalize(r.content()),"recordId",r.id()));
        return changes; // Additions are always performed before removals.
    }
}
