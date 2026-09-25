package com.exportautomation.service;

import com.exportautomation.dto.BatchClassifyResponse;
import com.exportautomation.dto.ClassificationResult;
import com.exportautomation.dto.ClassifyBuyerResponse;
import com.exportautomation.entity.Buyer;
import com.exportautomation.exception.ResourceNotFoundException;
import com.exportautomation.repository.BuyerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class LeadClassificationService {

    private static final Logger logger = LoggerFactory.getLogger(LeadClassificationService.class);

    private static final Set<String> VALID_CATEGORIES = new HashSet<>(Arrays.asList(
            "IMPORTER", "DISTRIBUTOR", "WHOLESALER", "RETAILER",
            "BUSINESS_BUYER", "INDIVIDUAL_BUYER", "OTHER"
    ));

    private static final Set<String> VALID_PRIORITIES = new HashSet<>(Arrays.asList(
            "HIGH_PRIORITY", "MEDIUM_PRIORITY", "LOW_PRIORITY"
    ));

    @Autowired
    private GeminiService geminiService;

    @Autowired
    private BuyerRepository buyerRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClassifyBuyerResponse classifyBuyer(Long buyerId) {
        Buyer buyer = buyerRepository.findById(buyerId)
                .orElseThrow(() -> new ResourceNotFoundException("Buyer not found with id: " + buyerId));

        String prompt = buildClassificationPrompt(buyer);
        String aiResponse = geminiService.generateContent(prompt);
        ClassificationResult result = parseAndValidateResponse(aiResponse);

        buyer.setClassification(result.getBusinessCategory());
        buyer.setLeadPriority(result.getLeadPriority());
        buyer.setClassificationReason(result.getReason());
        buyerRepository.save(buyer);

        return new ClassifyBuyerResponse(
                buyer.getId(),
                result.getBusinessCategory(),
                result.getLeadPriority(),
                result.getReason()
        );
    }

    public BatchClassifyResponse classifyAllUnclassified() {
        List<Buyer> unclassifiedBuyers = buyerRepository.findByClassificationIsNull();

        BatchClassifyResponse summary = new BatchClassifyResponse();
        summary.setTotal(unclassifiedBuyers.size());

        for (Buyer buyer : unclassifiedBuyers) {
            try {
                String prompt = buildClassificationPrompt(buyer);
                String aiResponse = geminiService.generateContent(prompt);
                ClassificationResult result = parseAndValidateResponse(aiResponse);

                buyer.setClassification(result.getBusinessCategory());
                buyer.setLeadPriority(result.getLeadPriority());
                buyer.setClassificationReason(result.getReason());
                buyerRepository.save(buyer);

                summary.setClassified(summary.getClassified() + 1);
            } catch (Exception e) {
                logger.error("Failed to classify buyer {}: {}", buyer.getId(), e.getMessage());
                summary.setFailed(summary.getFailed() + 1);
                summary.addFailure(buyer.getId(), e.getMessage());
            }
        }

        return summary;
    }

    private String buildClassificationPrompt(Buyer buyer) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are classifying potential international export buyers.\n");
        sb.append("Analyze the supplied buyer information below.\n\n");
        sb.append("Buyer Information:\n");

        appendField(sb, "Buyer Name", buyer.getBuyerName());
        appendField(sb, "Company Name", buyer.getCompanyName());
        appendField(sb, "Country", buyer.getCountry());
        appendField(sb, "Website", buyer.getWebsite());
        sb.append("- Business Type: ").append(safeValue(buyer.getBusinessType())).append("\n");
        appendField(sb, "Product Name", buyer.getProductName());
        appendField(sb, "Source Platform", buyer.getSourcePlatform());
        appendField(sb, "Source URL", buyer.getSourceUrl());

        sb.append("\nInstructions:\n");
        sb.append("Choose exactly ONE business category from this list:\n");
        sb.append("IMPORTER, DISTRIBUTOR, WHOLESALER, RETAILER, BUSINESS_BUYER, INDIVIDUAL_BUYER, OTHER\n\n");
        sb.append("Choose exactly ONE lead priority from this list:\n");
        sb.append("HIGH_PRIORITY, MEDIUM_PRIORITY, LOW_PRIORITY\n\n");
        sb.append("Priority guidance:\n");
        sb.append("- HIGH_PRIORITY: Company appears directly relevant to the product, importer/distributor/wholesaler with strong product relevance, clear business contact information.\n");
        sb.append("- MEDIUM_PRIORITY: Potentially relevant business, some useful information available, relevance is not completely clear.\n");
        sb.append("- LOW_PRIORITY: Weak relevance, incomplete information, individual/unrelated business, insufficient evidence.\n\n");
        sb.append("Never invent facts. Do not infer information that is not provided.\n\n");
        sb.append("Return ONLY a valid JSON object with exactly these three keys:\n");
        sb.append("{\"businessCategory\": \"...\", \"leadPriority\": \"...\", \"reason\": \"...\"}");

        return sb.toString();
    }

    private void appendField(StringBuilder sb, String label, String value) {
        sb.append("- ").append(label).append(": ").append(safeValue(value)).append("\n");
    }

    private String safeValue(String value) {
        return (value == null || value.trim().isEmpty()) ? "Not provided" : value.trim();
    }

    private ClassificationResult parseAndValidateResponse(String response) {
        try {
            String cleaned = response.trim();

            int jsonStart = cleaned.indexOf('{');
            int jsonEnd = cleaned.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                cleaned = cleaned.substring(jsonStart, jsonEnd + 1);
            }

            JsonNode root = objectMapper.readTree(cleaned);

            String businessCategory = root.path("businessCategory").asText("").trim().toUpperCase();
            String leadPriority = root.path("leadPriority").asText("").trim().toUpperCase();
            String reason = root.path("reason").asText("").trim();

            if (!VALID_CATEGORIES.contains(businessCategory)) {
                logger.warn("Invalid business category from AI: '{}', defaulting to OTHER", businessCategory);
                businessCategory = "OTHER";
            }

            if (!VALID_PRIORITIES.contains(leadPriority)) {
                logger.warn("Invalid lead priority from AI: '{}', defaulting to LOW_PRIORITY", leadPriority);
                leadPriority = "LOW_PRIORITY";
            }

            if (reason.isEmpty()) {
                reason = "Classification determined by AI analysis.";
            }

            return new ClassificationResult(businessCategory, leadPriority, reason);

        } catch (Exception e) {
            logger.error("Failed to parse AI classification response: {}", e.getMessage());
            throw new RuntimeException("Failed to parse AI classification response: " + e.getMessage(), e);
        }
    }
}
