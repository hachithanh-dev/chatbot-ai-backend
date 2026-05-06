package dev.thanh.spring_ai.config;

import dev.thanh.spring_ai.advisor.MemoryRetentionAdvisor;
import dev.thanh.spring_ai.tools.AgentThinking;
import dev.thanh.spring_ai.tools.DateTimeTools;
import dev.thanh.spring_ai.tools.JavaKnowledgeTools;
import dev.thanh.spring_ai.tools.MemoryTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.augment.AugmentedToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Cấu hình ChatClient với:
 * <ul>
 *   <li><b>AugmentedToolCallbackProvider</b> — wrap TẤT CẢ tools với reasoning logger.
 *       Mọi tool call đều tự động log innerThought + confidence.</li>
 *   <li><b>MemoryRetentionAdvisor</b> — StreamAroundAdvisor trigger memory extraction
 *       sau khi stream hoàn tất.</li>
 *   <li><b>cheapChatClient</b> — ChatClient riêng dùng model rẻ cho memory extraction.</li>
 * </ul>
 */
@Configuration
@Slf4j
@ConditionalOnProperty(name = "llm.mock.enabled", havingValue = "false", matchIfMissing = true)
public class AiConfig {

    /**
     * ChatClient chính — {@code @Primary} để Spring ưu tiên khi inject mặc định.
     * <p>
     * <b>QUAN TRỌNG:</b> {@code AugmentedToolCallbackProvider.Builder.toolObject()}
     * chỉ nhận <b>MỘT</b> object duy nhất (mỗi lần gọi sẽ ghi đè object trước).
     * Để wrap NHIỀU tool objects, phải dùng {@code ToolCallbacks.from()} tạo composite
     * provider rồi truyền qua {@code .delegate()}.
     */
    @Bean
    @Primary
    public ChatClient chatClient(ChatClient.Builder builder,
                                  JavaKnowledgeTools knowledgeTool,
                                  MemoryTools memoryTools,
                                  MemoryRetentionAdvisor memoryAdvisor) {

        // Bước 1: Gộp TẤT CẢ tool objects vào composite provider.
        // ToolCallbacks.from(Object...) scan @Tool methods từ mỗi object
        // → trả ToolCallback[] chứa TẤT CẢ tools đã resolve.
        ToolCallbackProvider composite = ToolCallbackProvider.from(
                ToolCallbacks.from(knowledgeTool, memoryTools, new DateTimeTools())
        );

        // Bước 2: Wrap composite provider bằng AugmentedToolCallbackProvider.
        // Tất cả tools trong composite đều nhận thêm AgentThinking schema
        // để LLM trả về innerThought + confidence cùng tool arguments.
        AugmentedToolCallbackProvider<AgentThinking> augmentedProvider =
                AugmentedToolCallbackProvider.<AgentThinking>builder()
                        .delegate(composite)
                        .argumentType(AgentThinking.class)
                        .argumentConsumer(event -> {
                            AgentThinking thinking = event.arguments();
                            String toolName = event.toolDefinition().name();

                            if (toolName.contains("search") || toolName.contains("knowledge")) {
                                log.info("📚 [RAG] Tool={} | Confidence={} | Reason={}",
                                        toolName, thinking.confidence(), thinking.innerThought());
                            } else if (toolName.contains("memory") || toolName.contains("Memory")) {
                                log.info("🧠 [MEM] Tool={} | Confidence={} | Reason={}",
                                        toolName, thinking.confidence(), thinking.innerThought());
                            } else {
                                log.info("🔧 [TOOL] Tool={} | Confidence={} | Reason={}",
                                        toolName, thinking.confidence(), thinking.innerThought());
                            }
                        })
                        .build();

        // Debug: verify tool registration at startup
        ToolCallback[] callbacks = augmentedProvider.getToolCallbacks();
        log.info("🔍 [TOOL-REGISTRY] Registered {} tools:", callbacks.length);
        for (ToolCallback cb : callbacks) {
            log.info("  ✅ name='{}', desc='{}'",
                    cb.getToolDefinition().name(),
                    cb.getToolDefinition().description().substring(0,
                            Math.min(80, cb.getToolDefinition().description().length())));
        }

        return builder
                .defaultToolCallbacks(augmentedProvider)
                .defaultAdvisors(memoryAdvisor)
                .build();
    }

    /**
     * Cheap ChatClient — dùng riêng cho Memory Extraction.
     * <p>
     * Dùng {@code builder.clone()} để tách biệt hoàn toàn khỏi chatClient,
     * tránh mutate Builder chung (gây mất advisor/tool config).
     * Không cần tool, không cần advisor.
     */
    @Bean
    @Qualifier("cheapChatClient")
    public ChatClient cheapChatClient(ChatClient.Builder builder) {
        return builder.clone()
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .model("gemini-2.5-flash-lite")
                        .build())
                .build();
    }
}