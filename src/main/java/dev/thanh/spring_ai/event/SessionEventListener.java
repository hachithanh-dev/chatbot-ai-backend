package dev.thanh.spring_ai.event;

import dev.thanh.spring_ai.service.SessionCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Lắng nghe các Spring Application Events liên quan đến Session.
 *
 * Dùng @TransactionalEventListener với phase = AFTER_COMMIT để đảm bảo
 * chỉ write vào cache SAU KHI transaction DB đã commit thành công,
 * tránh tình trạng cache có "phantom data" khi transaction bị rollback.
 */
@Component
@Slf4j(topic = "SESSION-EVENT-LISTENER")
@RequiredArgsConstructor
public class SessionEventListener {

    private final SessionCacheService sessionCacheService;

    /**
     * Ghi sessionId vào cache sau khi transaction tạo session đã commit thành công.
     * Chỉ cache sessionId (String) — tối ưu bộ nhớ Redis.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSessionCreated(SessionCreatedEvent event) {
        var session = event.getSession();
        if (session == null) {
            log.warn("Session is null in SessionCreatedEvent");
            return;
        }
        var id = session.getId();
        if (id == null) {
            log.warn("Session ID is null in SessionCreatedEvent");
            return;
        }
        String sessionId = id.toString();
        sessionCacheService.cacheSessionId(sessionId);
        log.info("Session [{}] cached after DB commit for user [{}]",
                sessionId, session.getUserId());
    }
}
