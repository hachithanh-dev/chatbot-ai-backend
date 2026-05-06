package dev.thanh.spring_ai.tools;

import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * Record cho augmented arguments — LLM sẽ điền reasoning khi gọi tool.
 * <p>
 * Fields này được thêm vào JSON Schema của mọi tool một cách "vô hình":
 * tool gốc KHÔNG nhận được, chỉ Consumer nhận.
 */
public record AgentThinking(
        @ToolParam(description = "Your step-by-step reasoning for why you're calling this tool", required = true)
        String innerThought,

        @ToolParam(description = "Confidence level in this tool choice: low, medium, high", required = false)
        String confidence,

        @ToolParam(description = "Key insights about the user worth remembering for future interactions", required = false)
        List<String> memoryNotes
) {}
