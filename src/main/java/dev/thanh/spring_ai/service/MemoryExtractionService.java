package dev.thanh.spring_ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Service trích xuất facts từ tin nhắn user bằng cheap LLM.
 * <p>
 * Chạy async trên virtual thread sau khi LLM response xong — zero latency impact.
 * <p>
 * <b>Fail-Open:</b> Mọi exception đều được catch — extraction failure
 * không bao giờ ảnh hưởng đến chat pipeline.
 */
@Service
@Slf4j(topic = "MEMORY-EXTRACTION")
@ConditionalOnProperty(name = "memory.enabled", havingValue = "true", matchIfMissing = true)
public class MemoryExtractionService {

    private static final String EXTRACTION_PROMPT = """
            You are a fact extractor. Given a user message, determine if it contains any \
            personally identifiable or long-term relevant facts ABOUT the user.
            
            EXTRACT these categories:
            - Identity: name, age, nickname, gender
            - Education: school, major, year of study, degree
            - Career: occupation, company, role, expertise level
            - Technical: tech stack, programming languages, frameworks
            - Projects: current projects, side projects, what they are building
            - Goals & Interests: learning goals, career goals, hobbies, strong preferences
            
            DO NOT extract: questions, requests, greetings, opinions about external topics, \
            code snippets, or temporary conversational context.
            
            Reply with exactly "NONE" if nothing worth saving.
            Otherwise reply with ONE concise sentence in ENGLISH that captures ALL \
            relevant facts about the user. No explanation.
            
            User message: %s
            """;

    private final ChatClient cheapChatClient;
    private final UserMemoryService memoryService;

    public MemoryExtractionService(
            @Qualifier("cheapChatClient") ChatClient cheapChatClient,
            UserMemoryService memoryService) {
        this.cheapChatClient = cheapChatClient;
        this.memoryService = memoryService;
    }

    /**
     * Phân tích tin nhắn user, trích xuất facts và lưu vào long-term memory.
     * <p>
     * Chạy async trên virtual thread — zero impact lên response latency.
     */
    @Async("virtualThreadExecutor")
    public void processAndSaveMemoryAsync(String userId, String userMessage) {
        try {
            String fact = cheapChatClient.prompt()
                    .user(EXTRACTION_PROMPT.formatted(userMessage))
                    .call().content();

            if (fact == null || fact.isBlank() || fact.trim().equalsIgnoreCase("NONE")) {
                log.debug("[MEMORY] No fact worth saving from: [{}]",
                        userMessage.length() > 60 ? userMessage.substring(0, 60) + "..." : userMessage);
                return;
            }

            String cleanFact = fact.trim();
            log.info("📝 [MEMORY] New fact for user {}: {}", userId, cleanFact);
            memoryService.storeMemory(userId, cleanFact);

        } catch (Exception e) {
            log.warn("⚠️ [MEMORY] Extraction failed for user {}: {}", userId, e.getMessage());
        }
    }
}
