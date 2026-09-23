package com.admin.config;

import com.admin.common.utils.WebSocketServer;
import com.admin.common.utils.VpsTerminalWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;


@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private WebSocketInterceptor webSocketInterceptor;

    @Resource
    private VpsTerminalHandshakeInterceptor vpsTerminalHandshakeInterceptor;

    @Resource
    private VpsTerminalWebSocketHandler vpsTerminalWebSocketHandler;

    @Value("${vps.terminal.allowed-origins:}")
    private String terminalAllowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry webSocketHandlerRegistry) {
        webSocketHandlerRegistry
                .addHandler(myHandler(), "/system-info")
                .setAllowedOrigins("*")
                .addInterceptors(webSocketInterceptor);
        WebSocketHandlerRegistration terminalRegistration = webSocketHandlerRegistry
                .addHandler(vpsTerminalWebSocketHandler, "/vps-terminal");
        String[] origins = Arrays.stream((terminalAllowedOrigins == null ? "" : terminalAllowedOrigins).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toArray(String[]::new);
        if (origins.length > 0) {
            terminalRegistration.setAllowedOrigins(origins);
        }
        // Without an explicit allow-list Spring keeps its same-origin default.
        terminalRegistration.addInterceptors(vpsTerminalHandshakeInterceptor);
    }


    @Bean
    public WebSocketHandler myHandler() {
        return new WebSocketServer();
    }

}
