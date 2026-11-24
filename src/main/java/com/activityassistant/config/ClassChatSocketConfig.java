package com.activityassistant.config;

import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.service.AuthService;
import com.activityassistant.service.ClassChatService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableWebSocket
public class ClassChatSocketConfig implements WebSocketConfigurer {
    private final ClassChatSocket socket;
    private final ClassChatService chat;

    public ClassChatSocketConfig(ClassChatSocket socket, ClassChatService chat) {
        this.socket = socket;
        this.chat = chat;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(socket, "/ws/chat").addInterceptors(new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                           WebSocketHandler handler, Map<String, Object> attributes) {
                String classId = request instanceof ServletServerHttpRequest servlet
                        ? servlet.getServletRequest().getParameter("classId") : null;
                if (classId == null || !classId.matches("[A-Za-z0-9_-]{1,32}")) {
                    response.setStatusCode(HttpStatus.BAD_REQUEST);
                    return false;
                }
                if (!(request.getPrincipal() instanceof Authentication auth)
                        || !(auth.getPrincipal() instanceof AccountPrincipal account)
                        || !(auth.getCredentials() instanceof String token)
                        || !chat.canEnter(classId, account)) {
                    response.setStatusCode(HttpStatus.FORBIDDEN);
                    return false;
                }
                attributes.put("classId", classId);
                attributes.put("token", token);
                return true;
            }

            @Override
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler handler, Exception exception) {}
        });
    }
}

@Component
class ClassChatSocket extends TextWebSocketHandler {
    private static final TextMessage CHANGED = new TextMessage("messages_changed");

    private final AuthService auth;
    private final ClassChatService chat;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    ClassChatSocket(AuthService auth, ClassChatService chat) {
        this.auth = auth;
        this.chat = chat;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(128);
        String classId = (String) session.getAttributes().get("classId");
        String token = (String) session.getAttributes().remove("token");
        var connection = new Connection(classId, token, new ConcurrentWebSocketSessionDecorator(session, 3000, 1024));
        // 登记这个窗口所连接的班级，之后按班级找到对应窗口。
        connections.put(session.getId(), connection);
        // A fresh signal after registration closes the gap between history and connection setup.
        notifyClient(connection);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        var connection = connections.get(session.getId());
        if (connection != null) close(connection, CloseStatus.SERVER_ERROR);
    }

    // 消息保存成功后，提醒正在查看同一班级群聊的窗口。
    @TransactionalEventListener
    public void messagesChanged(ClassChatService.MessagesChanged event) {
        for (var connection : connections.values()) {
            if (connection.classId().equals(event.classId())) notifyClient(connection);
        }
    }

    private void notifyClient(Connection connection) {
        try {
            var account = auth.findByRawToken(connection.token()).orElse(null);
            if (account == null || account.forcePasswordChange() > 0 || !chat.canEnter(connection.classId(), account)) {
                close(connection, CloseStatus.POLICY_VIOLATION);
                return;
            }
            connection.session().sendMessage(CHANGED);
        } catch (IOException | RuntimeException exception) {
            close(connection, CloseStatus.SERVER_ERROR);
        }
    }

    private void close(Connection connection, CloseStatus status) {
        connections.remove(connection.session().getId(), connection);
        try {
            connection.session().close(status);
        } catch (IOException ignored) {
            // The peer may already have gone away.
        }
    }

    private record Connection(String classId, String token, WebSocketSession session) {}
}
