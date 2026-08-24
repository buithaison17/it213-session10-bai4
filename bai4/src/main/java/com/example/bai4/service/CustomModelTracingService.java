package com.example.bai4.service;

import com.example.bai4.config.LangfuseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomModelTracingService {

    private static final Logger log = LoggerFactory.getLogger(CustomModelTracingService.class);

    private final RestClient restClient;

    public CustomModelTracingService(LangfuseProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeaders(headers -> headers.setBasicAuth(properties.getPublicKey(), properties.getSecretKey()))
                .build();
    }

    /**
     * Thực hiện truy vấn RAG và gửi chi tiết Latency + Token Usage thủ công sang Langfuse
     */
    public String executeRAGPipeline(String sessionId, String userId, String userQuery) {
        String traceId = UUID.randomUUID().toString();
        Instant traceStartTime = Instant.now();

        // 1. Tạo Trace Root
        sendTraceCreate(traceId, sessionId, userId, userQuery, traceStartTime);

        // 2. Bước 1: Retrieval từ Vector DB (Đo độ trễ Retrieval)
        Instant retrievalStart = Instant.now();
        List<String> contexts = mockVectorDbRetrieval(userQuery);
        Instant retrievalEnd = Instant.now();

        sendSpanEvent(traceId, "vector-db-retrieval", "RETRIEVAL",
                Map.of("query", userQuery),
                Map.of("retrieved_chunks", contexts.size()),
                retrievalStart, retrievalEnd);

        // 3. Bước 2: Gọi LLM Custom (DeepSeek-V3 / Model Local) & tính Token thủ công
        Instant generationStart = Instant.now();
        String prompt = "Context: " + String.join("\n", contexts) + "\nUser Query: " + userQuery;

        String llmResponse = mockCustomLLMCall(prompt);
        Instant generationEnd = Instant.now();

        // Ước tính / đếm số lượng token thủ công
        int inputTokens = countTokensManually(prompt);
        int outputTokens = countTokensManually(llmResponse);
        int totalTokens = inputTokens + outputTokens;

        // Gửi Generation Event kèm Token Usage
        sendGenerationEvent(traceId, "custom-llm-generation", "deepseek-v3",
                prompt, llmResponse, inputTokens, outputTokens, totalTokens,
                generationStart, generationEnd);

        log.info("RAG Trace hoàn tất: traceId={}, totalTokens={}", traceId, totalTokens);
        return llmResponse;
    }

    private void sendTraceCreate(String traceId, String sessionId, String userId, String input, Instant startTime) {
        Map<String, Object> body = Map.of(
                "id", traceId,
                "name", "rag-financial-advisor",
                "sessionId", sessionId,
                "userId", userId,
                "input", input,
                "timestamp", startTime.toString()
        );
        sendIngestionEvent("trace-create", body);
    }

    private void sendSpanEvent(String traceId, String name, String type,
                               Object input, Object output,
                               Instant startTime, Instant endTime) {
        Map<String, Object> body = Map.of(
                "id", UUID.randomUUID().toString(),
                "traceId", traceId,
                "name", name,
                "startTime", startTime.toString(),
                "endTime", endTime.toString(),
                "input", input,
                "output", output,
                "metadata", Map.of("step_type", type)
        );
        sendIngestionEvent("span-create", body);
    }

    private void sendGenerationEvent(String traceId, String name, String model,
                                     String inputPrompt, String outputText,
                                     int inputTokens, int outputTokens, int totalTokens,
                                     Instant startTime, Instant endTime) {
        // Cấu trúc usage thủ công theo chuẩn Langfuse Ingestion API
        Map<String, Object> usage = Map.of(
                "input", inputTokens,
                "output", outputTokens,
                "total", totalTokens,
                "unit", "TOKENS"
        );

        Map<String, Object> body = Map.of(
                "id", UUID.randomUUID().toString(),
                "traceId", traceId,
                "name", name,
                "model", model, // Khớp với model name đã tạo cấu hình giá trên Dashboard
                "input", inputPrompt,
                "output", outputText,
                "usage", usage,
                "startTime", startTime.toString(),
                "endTime", endTime.toString()
        );

        sendIngestionEvent("generation-create", body);
    }

    private void sendIngestionEvent(String eventType, Map<String, Object> body) {
        try {
            Map<String, Object> event = Map.of(
                    "id", UUID.randomUUID().toString(),
                    "type", eventType,
                    "timestamp", Instant.now().toString(),
                    "body", body
            );
            restClient.post()
                    .uri("/api/public/ingestion")
                    .body(Map.of("batch", List.of(event)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Lỗi gửi telemetry sang Langfuse: {}", e.getMessage());
        }
    }

    private List<String> mockVectorDbRetrieval(String query) {
        try {
            Thread.sleep(150); // Giả lập độ trễ vector search 150ms
        } catch (InterruptedException ignored) {
        }
        return List.of("Quy định hạn mức chuyển khoản tối đa 500 triệu/ngày.", "Phí chuyển khoản Napas247 là 0 VND.");
    }

    private String mockCustomLLMCall(String prompt) {
        try {
            Thread.sleep(850); // Giả lập độ trễ LLM Generation 850ms
        } catch (InterruptedException ignored) {
        }
        return "Hạn mức chuyển tiền trong ngày của bạn là 500 triệu VND và hoàn toàn miễn phí giao dịch.";
    }

    /**
     * Hàm ước lượng token thủ công: ~4 ký tự = 1 token
     */
    private int countTokensManually(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 4.0);
    }
}