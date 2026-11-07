package com.admin.common.service;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirportObserverIdentityTest {
    final long now=1800000000000L;
    @Test void overlappingMachinesPauseThenRetireTheQuietSourcePersistently() {
        var s=new AirportObserverIdentity();
        assertTrue(s.accept("old", "key",1,now));
        assertFalse(s.accept("new", "key",2,now+10000));assertTrue(s.conflict);
        assertFalse(s.accept("old", "key",3,now+20000));
        assertFalse(s.accept("new", "key",4,now+30000));
        assertTrue(s.accept("new", "key",5,now+65000));assertFalse(s.conflict);
        s=JSON.parseObject(JSON.toJSONString(s),AirportObserverIdentity.class);
        assertFalse(s.accept("old", "key",6,now+80000));assertEquals("new",s.active);assertFalse(s.conflict);
    }
    @Test void firstReplacementNeedsQuietPeriodButRepeatedRequestsCannotExtendLiveness() {
        var s=new AirportObserverIdentity();s.accept("old","key",10,now);
        assertFalse(s.accept("old","key",10,now+40000));
        assertTrue(s.accept("new","key",11,now+45000));
        assertFalse(s.accept("old","key",12,now+90000));
    }
    @Test void multipleContendersCannotWinByAlternatingAndFencesSurviveSerialization() {
        var s=new AirportObserverIdentity();s.accept("a","key",1,now);
        for (int i=1;i<20;i++) assertFalse(s.accept(i%2==0?"b":"c","key",i+1,now+i*10000));
        assertTrue(s.conflict);
    }
    @Test void credentialRotationResetsIdentityButCapacityDoesNotEvictRetiredMachines() {
        var s=new AirportObserverIdentity();s.accept("a","key",1,now);
        for(int i=0;i<256;i++) s.retired.add("retired"+i);
        assertFalse(s.accept("b","key",2,now+60000));assertTrue(s.capacityExceeded);
        assertTrue(s.accept("b","new-key",3,now+70000));assertTrue(s.retired.isEmpty());
    }
}
