package com.admin.common.service;

import com.admin.common.dto.GostConfigDto;
import com.admin.common.dto.ConfigItem;
import com.admin.common.utils.WebSocketServer;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrafficPoolInventoryTest {
    GostConfigDto config(String name){var c=new GostConfigDto();var item=new ConfigItem();item.setName(name);c.setServices(List.of(item));return c;}
    NodeContinuityObservation.Sample sample(String session,long at){return new NodeContinuityObservation.Sample("panel-epoch",session,at,100,2,1000,10000);}
    @Test void replacementConnectionCannotReuseOldMachinesInventory(){
        try(var sockets=mockStatic(WebSocketServer.class)){
            sockets.when(()->WebSocketServer.continuitySample(9001L,1000L)).thenReturn(sample("old",1000));
            TrafficPoolInventory.record(9001,config("old_tcp"),1000);assertEquals(Set.of("old_tcp"),TrafficPoolInventory.names(9001,1000));
            sockets.when(()->WebSocketServer.continuitySample(9001L,2000L)).thenReturn(sample("new",2000));
            assertNull(TrafficPoolInventory.names(9001,2000));
            TrafficPoolInventory.record(9001,config("new_tcp"),2000);assertEquals(Set.of("new_tcp"),TrafficPoolInventory.names(9001,2000));
        }
    }
    @Test void inventoryWithoutFreshAuthenticatedControlSessionCannotAdmitNewMember(){
        try(var sockets=mockStatic(WebSocketServer.class)){
            TrafficPoolInventory.record(9002,config("some_tcp"),1000);assertNull(TrafficPoolInventory.names(9002,1000));
        }
    }
}
