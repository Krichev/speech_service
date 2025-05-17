package yandex.cloud.speechkit.examples.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import yandex.cloud.speechkit.examples.service.WebSocketSpeechController;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    private final WebSocketSpeechController webSocketSpeechController;

    public WebSocketConfig(WebSocketSpeechController webSocketSpeechController) {
        this.webSocketSpeechController = webSocketSpeechController;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketSpeechController, "/speechkit/stt")
                .setAllowedOrigins("*"); // In production, restrict to specific origins
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // Set maximum message size to 64KB
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        container.setMaxTextMessageBufferSize(16 * 1024);
        // Set timeout values
        container.setAsyncSendTimeout(5000L);
        container.setMaxSessionIdleTimeout(300000L);
        return container;
    }
}