package com.admin.common.utils;

import com.admin.common.lang.R;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** Prevent monitoring results from bypassing the normal tenant address projection. */
public final class UserDiagnosisView {
    private UserDiagnosisView() {}

    public static R restrict(R result) {
        if (result == null || result.getData() == null || result.getCode() != 0) return result;
        JSONObject data = JSONObject.parseObject(JSON.toJSONString(result.getData()));
        redactReport(data);
        JSONArray forwards = data.getJSONArray("forwards");
        if (forwards != null) for (int i = 0; i < forwards.size(); i++) redactReport(forwards.getJSONObject(i));
        R view = R.ok(data);
        view.setTs(result.getTs());
        view.setMsg(result.getMsg());
        return view;
    }

    private static void redactReport(JSONObject report) {
        if (report == null) return;
        // Free-form Agent/exception text can include server IPs and default DNS names.
        if (report.containsKey("message")) report.put("message", "检测状态已更新，详细节点信息请联系管理员");
        JSONArray probes = report.getJSONArray("results");
        if (probes == null) return;
        for (int i = 0; i < probes.size(); i++) {
            JSONObject probe = probes.getJSONObject(i);
            if (probe == null) continue;
            String description = probe.getString("description");
            probe.remove("nodeId");
            probe.put("nodeName", description != null && description.startsWith("出口") ? "出口节点" : "入口节点");
            if ("入口->出口".equals(description)) {
                probe.remove("targetIp");
                probe.remove("targetPort");
            }
            probe.put("message", probe.getBooleanValue("success") ? "链路检测通过" : "链路检测未通过");
        }
    }
}
