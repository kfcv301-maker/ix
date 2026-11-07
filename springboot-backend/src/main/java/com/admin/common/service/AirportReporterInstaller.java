package com.admin.common.service;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class AirportReporterInstaller {
    private final AirportSyncService sync;
    public AirportReporterInstaller(AirportSyncService sync) { this.sync=sync; }
    public JSONObject installation(long nodeId) {
        if (!sync.needsReporter(nodeId)) return null;
        try (var source=new ClassPathResource("node-runtime/airport_address_reporter.py").getInputStream()) {
            byte[] bytes=source.readAllBytes();
            return JSONObject.of("enabled",true,"scriptBase64",Base64.getEncoder().encodeToString(bytes),
                    "sha256",AirportSyncState.digest(new String(bytes,StandardCharsets.UTF_8)));
        } catch (java.io.IOException missing) { throw new IllegalStateException("Address reporter package unavailable"); }
    }
    public String shell(long nodeId) {
        JSONObject config=installation(nodeId);
        if (config==null) return "";
        return "\n# Optional node-to-panel address observer; never restarts the Agent.\n"
                +"command -v python3 >/dev/null || { apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3; }\n"
                +"airport_observer=$(mktemp) || exit 1\n"
                +"printf '%s' '"+config.getString("scriptBase64")+"' | base64 -d > \"$airport_observer\"\n"
                +"if python3 \"$airport_observer\" --install --root /etc/flux-panel-agent; then rm -f \"$airport_observer\"; else rm -f \"$airport_observer\"; echo 'Optional address observer installation failed; Agent is unchanged' >&2; exit 1; fi\n";
    }
}
