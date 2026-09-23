package com.admin.config;

import com.admin.common.utils.WebSocketServer;
import com.admin.common.utils.VpsTerminalWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import javax.annotation.Resource;


@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private WebSocketInterceptor webSocketInterceptor;

    @Resource
    private VpsTerminalHandshakeInterceptor vpsTerminalHandshakeInterceptor;

    @Resource
    private VpsTerminalWebSocketHandler vpsTerminalWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry webSocketHandlerRegistry) {
        webSocketHandlerRegistry
                .addHandler(myHandler(), "/system-info")
                .setAllowedOrigins("*")
                .addInterceptors(webSocketInterceptor);
        webSocketHandlerRegistry
                .addHandler(vpsTerminalWebSocketHandler, "/vps-terminal")
                .setAllowedOrigins("*")
                .addInterceptors(vpsTerminalHandshakeInterceptor);
    }


    @Bean
    public WebSocketHandler myHandler() {
        return new WebSocketServer();
    }

}
