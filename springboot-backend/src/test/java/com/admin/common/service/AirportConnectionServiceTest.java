package com.admin.common.service;

import com.admin.common.migration.AirportSyncMigration;
import com.admin.entity.User;
import com.admin.mapper.UserMapper;
import com.alibaba.fastjson2.JSONObject;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AirportConnectionServiceTest {
    final String remote="11111111-1111-1111-1111-111111111111",binding="22222222-2222-2222-2222-222222222222",token="a".repeat(64);
    JdbcTemplate jdbc; AirportConnectionService service;
    UserMapper users=mock(UserMapper.class);AirportSyncTransport transport=mock(AirportSyncTransport.class);
    @BeforeEach void setup() {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");jdbc=new JdbcTemplate(ds);
        new AirportSyncMigration(jdbc).migrate();
        var admin=new User();admin.setStatus(1);admin.setRoleId(0);when(users.selectById(1L)).thenReturn(admin);
        service=new AirportConnectionService(jdbc,users,transport,new DataSourceTransactionManager(ds),"isolated-encryption-key");
    }
    @AfterEach void cleanup() { jdbc.execute("DROP ALL OBJECTS"); }
    Map<String,Object> create() { return service.save(1L,new AirportConnectionService.Settings("","Airport A","https://airport.example",remote,token,true,0L)); }
    JSONObject receipt() { return JSONObject.of("protocol_version",2,"status","authorized","server_time",1800000000,"connection_id",remote,"relay_panel_id",service.panelId(),
        "mappings",List.of(JSONObject.of("binding_id",binding,"relay_node_id",26,"label","Japan entry","enabled",true))); }
    void respond(JSONObject data) { when(transport.exchange(anyString(),anyString(),anyString(),any())).thenReturn(new AirportSyncTransport.Reply("ok",JSONObject.of("data",data).toJSONString())); }
    Map<String,Object> check(String id) { return service.check(1L,id,service.load(id,false).revision); }
    @Test void panelIdentityPersistsAcrossMigrationAndOnlyAdminCanAccess() {
        String id=service.panelId();new AirportSyncMigration(jdbc).migrate();assertEquals(id,service.panelId());
        assertThrows(SecurityException.class,()->service.list(9L));assertThrows(SecurityException.class,()->service.save(9L,new AirportConnectionService.Settings("","bad","https://a.example",remote,token,true,0L)));
    }
    @Test void tokenEncryptedAndDestinationEditInvalidatesPairing() {
        var c=create();String id=(String)c.get("id");String raw=jdbc.queryForObject("SELECT payload FROM airport_connection WHERE id=?",String.class,id);
        assertFalse(raw.contains(token));assertFalse(JSONObject.toJSONString(service.list(1L)).contains("encryptedToken"));
        respond(receipt());assertEquals("connected",check(id).get("status"));
        assertThrows(IllegalArgumentException.class,()->service.save(1L,new AirportConnectionService.Settings(id,"B","https://other.example",remote,"",true,2L)));
        service.save(1L,new AirportConnectionService.Settings(id,"B","https://other.example",remote,"b".repeat(64),true,2L));
        assertFalse(service.allowed(service.load(id,false),26,binding));
        assertThrows(IllegalArgumentException.class,()->service.check(1L,id,1));
    }
    @Test void checkRejectsWrongPeerIdentityAndFiltersDisabledMappings() {
        String id=(String)create().get("id");var data=receipt();data.put("relay_panel_id",remote);respond(data);
        assertEquals("retry_receipt",check(id).get("status"));
        data=receipt();data.getJSONArray("mappings").getJSONObject(0).put("enabled",false);respond(data);
        check(id);assertFalse(service.allowed(service.load(id,false),26,binding));
        respond(receipt());check(id);assertTrue(service.allowed(service.load(id,false),26,binding));assertFalse(service.allowed(service.load(id,false),27,binding));
    }
    @Test void reportRequiresExactConnectionPanelNodeBindingAndAddressReceipt() {
        String id=(String)create().get("id");respond(receipt());check(id);var c=service.load(id,false);
        var data=receipt();data.remove("mappings");data.put("status","updated");data.put("updated",1);data.put("relay_node_id",26);data.put("binding_id",binding);data.put("ip","8.8.8.8");respond(data);
        assertEquals("synced",service.send(c,26,binding,"8.8.8.8",1800000000));
        assertEquals("blocked_connection",service.send(c,27,binding,"8.8.8.8",1800000000));
        data.put("relay_node_id",27);respond(data);assertEquals("retry_receipt",service.send(c,26,binding,"8.8.8.8",1800000000));
        service.save(1L,new AirportConnectionService.Settings(id,"A",c.origin,remote,"",false,c.revision));
        assertEquals("blocked_connection",service.send(service.load(id,false),26,binding,"8.8.8.8",1800000000));
    }
    @Test void rejectsNonHttpsPathsAndUnboundedMappingResponse() {
        assertThrows(IllegalArgumentException.class,()->AirportAddressPolicy.origin("http://airport.example"));
        assertThrows(IllegalArgumentException.class,()->AirportAddressPolicy.origin("https://airport.example/other"));
        String id=(String)create().get("id");var data=receipt();data.put("mappings",Collections.nCopies(101,data.getJSONArray("mappings").get(0)));respond(data);
        assertEquals("retry_receipt",check(id).get("status"));
    }
    @Test void combinedTokenResolvesConnectionIdWithoutManualUuidInput() {
        var saved=service.save(1L,new AirportConnectionService.Settings("","A","https://airport.example","","ns2."+remote+"."+token,true,0L));
        assertEquals(remote,saved.get("connectionId"));assertEquals(true,saved.get("hasToken"));
        assertFalse(JSONObject.toJSONString(service.list(1L)).contains(token));
    }
    @Test void phpGeneratedRealRouteFixturesMatchJavaCheckAndReport() throws Exception {
        JSONObject fixture;
        try(var stream=getClass().getResourceAsStream("/airport-v2-php-fixtures.json")) {
            assertNotNull(stream);fixture=JSONObject.parseObject(new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        var request=fixture.getJSONObject("check").getJSONObject("request");
        jdbc.update("UPDATE airport_panel_identity SET panel_id=? WHERE id=1",request.getString("relay_panel_id"));
        var c=service.save(1L,new AirportConnectionService.Settings("","PHP fixture","https://airport.example",request.getString("connection_id"),token,true,0L));
        String id=(String)c.get("id");respond(fixture.getJSONObject("check").getJSONObject("body").getJSONObject("data"));
        assertEquals("connected",check(id).get("status"));
        var report=fixture.getJSONObject("report");var r=report.getJSONObject("request");respond(report.getJSONObject("body").getJSONObject("data"));
        assertEquals("synced",service.send(service.load(id,false),r.getLongValue("relay_node_id"),r.getString("binding_id"),r.getString("ip"),r.getLongValue("observed_at")));
    }
    @Test void coercibleStringsAndMissingProtocolNeverPassPairingOrDelivery() {
        String id=(String)create().get("id");
        for(String field:List.of("protocol_version","relay_node_id","enabled")) {
            var data=receipt();
            if (field.equals("protocol_version")) data.put(field,"2");
            else { var b=data.getJSONArray("mappings").getJSONObject(0);b.put(field,field.equals("enabled")?"true":"26"); }
            respond(data);assertEquals("retry_receipt",check(id).get("status"));
        }
        respond(receipt());check(id);var c=service.load(id,false);
        var good=receipt();good.put("status","updated");good.put("relay_node_id",26);good.put("binding_id",binding);good.put("ip","8.8.8.8");good.put("updated",1);
        for (Object invalid:Arrays.asList(null,"1",-1,1.5)) {
            var d=JSONObject.parseObject(good.toJSONString());d.put("updated",invalid);respond(d);
            assertEquals("retry_receipt",service.send(c,26,binding,"8.8.8.8",1800000000));
        }
        good.remove("protocol_version");respond(good);assertEquals("retry_receipt",service.send(c,26,binding,"8.8.8.8",1800000000));
    }
}
