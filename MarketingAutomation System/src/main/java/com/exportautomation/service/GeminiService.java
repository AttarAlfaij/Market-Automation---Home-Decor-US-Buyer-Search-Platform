package com.exportautomation.service;

import com.exportautomation.config.GeminiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiService.class);

    @Autowired
    @Qualifier("geminiWebClient")
    private WebClient webClient;

    @Autowired
    private GeminiConfig geminiConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateContent(String prompt) {
        String url = "/v1beta/models/" + geminiConfig.getModel() + ":generateContent?key=" + geminiConfig.getApiKey();

        Map<String, Object> requestBody = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(Map.of("text", prompt)))
            ),
            "generationConfig", Map.of(
                "temperature", 0.2,
                "maxOutputTokens", 1024
            )
        );

        try {
            String responseJson = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(responseJson);

            if (root.has("error")) {
                String errorMessage = root.get("error").has("message")
                        ? root.get("error").get("message").asText()
                        : "Unknown Gemini API error";
                logger.error("Gemini API error: {}", errorMessage);
                throw new RuntimeException("Gemini API error: " + errorMessage);
            }

            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode content = candidates.get(0).path("content").path("parts");
                if (content.isArray() && content.size() > 0) {
                    return content.get(0).path("text").asText().trim();
                }
            }

            logger.warn("Unexpected Gemini response structure: {}", responseJson);
            throw new RuntimeException("Unexpected response structure from Gemini API");

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to communicate with Gemini API", e);
            throw new RuntimeException("Failed to communicate with Gemini API: " + e.getMessage(), e);
        }
    }
}
