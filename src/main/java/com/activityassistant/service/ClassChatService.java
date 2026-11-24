package com.activityassistant.service;

import com.activityassistant.persistence.Db;
import com.activityassistant.security.AccountPrincipal;
import com.activityassistant.support.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class ClassChatService {
    private static final String CAN_ENTER = """
            (EXISTS (SELECT 1 FROM class_members cm WHERE cm.class_id = c.id AND cm.account_id = ?)
             OR (? AND EXISTS (SELECT 1 FROM class_organizations co
                 JOIN organization_members om ON om.organization_id = co.organization_id
                 WHERE co.class_id = c.id AND om.account_id = ? AND om.role IN ('owner', 'manager'))))
            """;
    private static final String MESSAGES = """
            SELECT m.sequence, m.sender_id, a.display_name AS sender_name, m.body, m.created_at
            FROM class_chat_messages m JOIN accounts a ON a.id = m.sender_id WHERE m.class_id = ?
            """;

    private final Db db;
    private final ApplicationEventPublisher events;

    public ClassChatService(Db db, ApplicationEventPublisher events) {
        this.db = db;
        this.events = events;
    }

    public List<Map<String, Object>> rooms(AccountPrincipal account) {
        return db.list("SELECT c.id, c.name FROM classes c WHERE " + CAN_ENTER + " ORDER BY c.name, c.id",
                account.id(), account.isManager(), account.id());
    }

    public boolean canEnter(String classId, AccountPrincipal account) {
        return db.one("SELECT c.id FROM classes c WHERE c.id = ? AND " + CAN_ENTER,
                classId, account.id(), account.isManager(), account.id()).isPresent();
    }

    public List<Map<String, Object>> messages(String classId, AccountPrincipal account, Long after, Long before) {
        requireAccess(classId, account);
        if ((after != null && after < 0) || (before != null && before <= 0) || (after != null && before != null)) {
            throw ApiException.badRequest("无效的消息位置");
        }
        if (after != null) {
            return db.list(MESSAGES + " AND m.sequence > ? ORDER BY m.sequence LIMIT 100", classId, after);
        }
        var latest = before == null
                ? db.list(MESSAGES + " ORDER BY m.sequence DESC LIMIT 30", classId)
                : db.list(MESSAGES + " AND m.sequence < ? ORDER BY m.sequence DESC LIMIT 30", classId, before);
        return latest.reversed();
    }

    @Transactional
    public Map<String, Object> send(String classId, AccountPrincipal account, String clientId, String body) {
        requireAccess(classId, account);
        // The class row serializes sequence assignment and duplicate submissions within this room.
        db.required("SELECT id FROM classes WHERE id = ? FOR UPDATE", classId);
        var existing = db.one(MESSAGES + " AND m.sender_id = ? AND m.client_message_id = ?", classId, account.id(), clientId);
        if (existing.isPresent()) return existing.get();
        var next = db.required("SELECT COALESCE(MAX(sequence), 0) + 1 AS next FROM class_chat_messages WHERE class_id = ?", classId);
        long sequence = ((Number) next.get("next")).longValue();
        db.update("""
                INSERT INTO class_chat_messages (class_id, sequence, sender_id, client_message_id, body, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, classId, sequence, account.id(), clientId, body.strip(), Instant.now().toString());
        events.publishEvent(new MessagesChanged(classId));
        return db.required(MESSAGES + " AND m.sequence = ?", classId, sequence);
    }

    private void requireAccess(String classId, AccountPrincipal account) {
        if (!canEnter(classId, account)) throw ApiException.forbidden("你没有权限进入该班级群聊");
    }

    public record MessagesChanged(String classId) {}
}
