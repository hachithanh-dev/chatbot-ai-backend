package dev.thanh.spring_ai.tools;

import dev.thanh.spring_ai.config.MemoryProperties;
import dev.thanh.spring_ai.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agentic Memory Tool — LLM tự quyết định khi nào cần recall user memories.
 * <p>
 * Tool này cho phép LLM truy xuất thông tin dài hạn về user từ Qdrant
 * collection {@code user-memories}. LLM gọi tool này khi câu hỏi cần
 * context cá nhân (sở thích, project, expertise) từ các session trước.
 * <p>
 * UserId được truyền an toàn qua {@code ToolContext} (từ ChatClient.userContext()),
 * KHÔNG dùng ThreadLocal.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "MEMORY-TOOL")
@ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
public class MemoryTools {

    private final UserMemoryService memoryService;
    private final MemoryProperties props;

    @Tool(name = "recall_memories", description = """
            Search long-term memory for information about the current user.
            Use this when the question benefits from knowing user preferences,
            past projects, expertise level, or context from previous conversations.
            Do NOT call for generic questions that don't need personal context.
            Returns relevant facts previously learned about this user.
            """)
    public String recallMemories(
            @ToolParam(description = "Search query — MUST be in ENGLISH regardless of user's language. Example: 'user name and current project'") String query,
            @SuppressWarnings("unused") org.springframework.ai.chat.model.ToolContext toolContext) {

        // Lấy userId an toàn từ ToolContext (truyền qua ChatClient.userContext())
        Map<String, Object> ctx = toolContext.getContext();
        String userId = ctx != null ? (String) ctx.get("userId") : null;

        if (userId == null) {
            log.warn("[RECALL] userId not found in ToolContext — cannot search memories");
            return "No memories found about this user.";
        }

        log.info("🧠 [MEMORY TOOL CALLED] Recalling memories for user={}, query=[{}]",
                userId, query.length() > 60 ? query.substring(0, 60) + "..." : query);

        return memoryService.searchMemories(userId, query, props.getTopK());
    }
}
