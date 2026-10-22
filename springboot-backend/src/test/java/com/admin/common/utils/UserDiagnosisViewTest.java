package com.admin.common.utils;

import com.admin.common.lang.R;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UserDiagnosisViewTest {
    @Test void syncAndBatchViewsHideInternalHopsAndErrorsWithoutMutatingStoredAdminReport() {
        Map<String, Object> report = Map.of("results", List.of(
                Map.of("nodeId", 12, "nodeName", "base.example.com", "targetIp", "192.0.2.5",
                        "targetPort", 1234, "description", "入口->出口", "success", false,
                        "message", "dial base.example.com 192.0.2.5 failed"),
                Map.of("nodeId", 13, "nodeName", "base.example.com", "targetIp", "user-target.example.com",
                        "targetPort", 443, "description", "出口->目标", "success", true)));
        R original = R.ok(report);
        for (R source : List.of(original, R.ok(Map.of("forwards", List.of(report))))) {
            String view = JSON.toJSONString(UserDiagnosisView.restrict(source));
            assertFalse(view.contains("base.example.com")); assertFalse(view.contains("192.0.2.5"));
            assertFalse(view.contains("nodeId")); assertTrue(view.contains("user-target.example.com"));
            assertTrue(JSON.toJSONString(source).contains("192.0.2.5"));
        }
        JSONObject probe = ((JSONObject) UserDiagnosisView.restrict(original).getData()).getJSONArray("results").getJSONObject(0);
        assertFalse(probe.containsKey("targetPort")); assertFalse(probe.getBooleanValue("success"));
    }
}
