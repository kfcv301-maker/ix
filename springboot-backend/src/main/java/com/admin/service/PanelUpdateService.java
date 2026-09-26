package com.admin.service;

import cn.hutool.core.util.StrUtil;
import com.admin.common.lang.R;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the internal-only updater container. The updater owns the Docker
 * socket, while the web process only holds a random shared token and never
 * executes shell commands supplied by the browser.
 */
@Service
public class PanelUpdateService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    @Value("${panel-updater.url}")
    private String updaterUrl;

    @Value("${panel-updater.token}")
    private String updaterToken;

    public R getStatus() {
        return request("/status", false);
    }

    public R startUpdate() {
        return request("/update", true);
    }

    private R request(String path, boolean post) {
        if (StrUtil.isBlank(updaterToken)) {
            return R.err("在线更新尚未初始化，请先通过面板安装脚本更新一次");
        }
        try {
            String baseUrl = updaterUrl == null ? "" : updaterUrl.replaceAll("/+$", "");
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(post ? 8 : 5))
                    .header("Authorization", "Bearer " + updaterToken)
                    .header("Accept", "application/json");
            if (post) {
                request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.noBody());
            } else {
                request.GET();
            }

            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return R.err("在线更新服务暂不可用，请稍后重试");
            }
            JSONObject result = JSONObject.parseObject(response.body());
            if (result == null) {
                return R.err("在线更新服务返回了无效数据");
            }
            return R.ok(result);
        } catch (Exception exception) {
            return R.err("无法连接在线更新服务，请确认面板已完成一次最新安装/更新");
        }
    }
}
