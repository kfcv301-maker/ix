package com.admin.common.service;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrafficPoolDnsTest {
    final String owner="lunaris-pool/fixture",zone="a".repeat(32),id="b".repeat(32),domain="pool.example.com";
    TrafficPoolDns dns=new TrafficPoolDns();
    TrafficPoolDns.Record record(String ip,String comment){return new TrafficPoolDns.Record(id,"A",domain,ip,comment,false);}
    @Test void foreignRecordsAndCnamesStopAllAutomaticWrites() {
        assertThrows(IllegalStateException.class,()->dns.plan(List.of(record("8.8.8.8","manual")),owner,List.of("1.1.1.1"),true));
        assertThrows(IllegalStateException.class,()->dns.plan(List.of(new TrafficPoolDns.Record(id,"CNAME",domain,"target.example.com","",false)),owner,List.of(),false));
        assertThrows(IllegalStateException.class,()->dns.plan(List.of(new TrafficPoolDns.Record(id,"A",domain,"8.8.8.8",owner,true)),owner,List.of("8.8.8.8"),true));
    }
    @Test void lastAddressProtectionAndAddBeforeDeleteAreDeterministic() {
        var records=List.of(record("8.8.8.8",owner));
        assertTrue(dns.plan(records,owner,List.of(),true).isEmpty());
        assertEquals("remove",dns.plan(records,owner,List.of(),false).getFirst().get("action"));
        var plan=dns.plan(records,owner,List.of("1.1.1.1"),true);
        assertEquals(List.of("add","remove"),plan.stream().map(c->c.get("action")).toList());
    }
    @Test void deletionRequiresCurrentExactOwnershipAndCannotDeleteExternallyEditedRecords() {
        var calls=new ArrayList<String>();
        var provider=new TrafficPoolDns(){@Override JSONObject request(String method,String path,String token,JSONObject body){calls.add(method);return JSONObject.of("result",JSONObject.of("id",id,"name",domain,"type","A","content","1.1.1.1","comment",owner));}};
        assertThrows(IllegalStateException.class,()->provider.delete(zone,record("8.8.8.8","manual"),domain,owner,"fixture"));assertTrue(calls.isEmpty());
        assertThrows(IllegalStateException.class,()->provider.delete(zone,record("8.8.8.8",owner),domain,owner,"fixture"));assertEquals(List.of("GET"),calls);
    }
    @Test void providerReceiptMustMatchNameAddressAndOwner() {
        var provider=new TrafficPoolDns(){@Override JSONObject request(String method,String path,String token,JSONObject body){return JSONObject.of("result",JSONObject.of("id",id,"name","other.example.com","content","8.8.8.8","comment",owner));}};
        assertThrows(IllegalStateException.class,()->provider.create(zone,domain,"8.8.8.8",60,owner,"fixture"));
    }
    @Test void maintenanceAndIndividualQuotaExcludeAddressesWithoutTouchingServices() {
        var member=new TrafficPoolState.Member();member.quotaBytes=100;member.usedBytes=99;
        var h=new TrafficPoolState.Health();h.eligible=true;member.health.put("8.8.8.8",h);
        assertEquals(List.of("8.8.8.8"),TrafficPoolDns.desired(List.of(member)));
        member.enabled=false;assertTrue(TrafficPoolDns.desired(List.of(member)).isEmpty());
        member.enabled=true;member.usedBytes=100;assertTrue(TrafficPoolDns.desired(List.of(member)).isEmpty());
    }
    @Test void adoptionChangesOnlyCommentAfterExactRecordRecheck() {
        var methods=new ArrayList<String>();
        var provider=new TrafficPoolDns(){@Override JSONObject request(String method,String path,String token,JSONObject body){
            methods.add(method);if("PATCH".equals(method))assertEquals(Set.of("comment"),body.keySet());
            return JSONObject.of("result",JSONObject.of("id",id,"type","A","name",domain,"content","8.8.8.8","comment","PATCH".equals(method)?owner:"manual","proxied",false));
        }};
        provider.adopt(zone,record("8.8.8.8","manual"),domain,owner,"fixture");assertEquals(List.of("GET","PATCH"),methods);
        assertThrows(IllegalStateException.class,()->provider.adopt(zone,record("8.8.8.8","lunaris-pool/someone-else"),domain,owner,"fixture"));assertEquals(2,methods.size());
    }
}
