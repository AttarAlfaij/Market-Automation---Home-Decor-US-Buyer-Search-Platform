package com.exportautomation.controller;

import com.exportautomation.dto.ApiResponse;
import com.exportautomation.dto.BuyerSearchRequest;
import com.exportautomation.dto.BuyerSearchResult;
import com.exportautomation.entity.Buyer;
import com.exportautomation.repository.BuyerRepository;
import com.exportautomation.service.FirecrawlBuyerSearchService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/buyers")
@CrossOrigin(origins = "*")
public class BuyerSearchController {

    private static final Logger logger = LoggerFactory.getLogger(BuyerSearchController.class);

    @Autowired
    private FirecrawlBuyerSearchService firecrawlBuyerSearchService;

    @Autowired
    private BuyerRepository buyerRepository;

    @PostMapping("/search-usa")
    public ResponseEntity<ApiResponse> searchUsaBuyers(@Valid @RequestBody BuyerSearchRequest request) {
        logger.info("Search request: productName='{}', limit={}, state='{}', city='{}', country='{}'",
                request.getProductName(), request.getResultLimit(),
                request.getState(), request.getCity(), request.getCountry());

        List<BuyerSearchResult> searchResults = firecrawlBuyerSearchService.searchBuyers(
                request.getProductName(), request.getResultLimit(),
                request.getState(), request.getCity(), request.getCountry());

        int totalFound = searchResults.size();
        int totalSaved = 0;
        int totalSkippedExisting = 0;
        int totalSkippedDuplicateEmail = 0;

        for (BuyerSearchResult result : searchResults) {
            if (result.getWebsite() != null && !result.getWebsite().isEmpty()
                    && buyerRepository.existsByWebsiteIgnoreCase(result.getWebsite())) {
                totalSkippedExisting++;
                logger.debug("SKIP '{}' - website '{}' already in database",
                        result.getCompanyName(), result.getWebsite());
                continue;
            }

            String normalizedEmail = null;
            if (result.getEmail() != null && !result.getEmail().isBlank()) {
                normalizedEmail = result.getEmail().trim().toLowerCase(Locale.ROOT);
            }
            if (normalizedEmail != null && buyerRepository.existsByEmailIgnoreCase(normalizedEmail)) {
                Optional<Buyer> existing = buyerRepository.findByEmailIgnoreCase(normalizedEmail);
                totalSkippedDuplicateEmail++;
                logger.info("DUPLICATE EMAIL | buyer='{}' | email={} | existing record={} (id={})"
                                + " | website={} - no INSERT attempted",
                        result.getCompanyName(), normalizedEmail,
                        existing.isPresent() ? existing.get().getCompanyName() : "n/a",
                        existing.isPresent() ? existing.get().getId() : "n/a",
                        result.getWebsite());
                continue;
            }

            Buyer buyer = new Buyer();
            buyer.setCompanyName(result.getCompanyName());
            buyer.setBuyerName(result.getBuyerName() != null ? result.getBuyerName() : result.getCompanyName());
            buyer.setWebsite(result.getWebsite());
            buyer.setCountry(result.getCountry() != null ? result.getCountry()
                    : (request.getCountry() != null && !request.getCountry().isBlank()
                            ? request.getCountry() : "USA"));
            buyer.setState(result.getState());
            buyer.setCity(result.getCity());
            buyer.setLocation(result.getLocation());
            buyer.setLocationSource(result.getLocationSource());
            buyer.setBusinessType(result.getBusinessType());

            String query = result.getSearchQuery();
            String sourcePlatform;
            if (query != null && query.contains("site:linkedin.com")) {
                sourcePlatform = "LinkedIn";
            } else if (query != null && query.contains("site:facebook.com")) {
                sourcePlatform = "Facebook";
            } else {
                sourcePlatform = "Google Search";
            }
            buyer.setSourcePlatform(sourcePlatform);
            buyer.setSourceUrl(result.getSourceUrl());
            buyer.setProductName(result.getProductName() != null ? result.getProductName() : request.getProductName());

            if (normalizedEmail != null) {
                buyer.setEmail(normalizedEmail);
            }

            try {
                buyerRepository.save(buyer);
                totalSaved++;
                logger.info("SAVED '{}' | website={} | email={} | type={} | source={}",
                        buyer.getCompanyName(), buyer.getWebsite(),
                        buyer.getEmail() != null ? buyer.getEmail() : "(none)",
                        buyer.getBusinessType(), sourcePlatform);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("Duplicate") || msg.contains("unique"))) {
                    totalSkippedDuplicateEmail++;
                    logger.info("SKIP '{}' - duplicate email: {}", result.getCompanyName(), msg);
                } else {
                    logger.error("Failed to save '{}': {}", result.getCompanyName(), msg);
                }
            }
        }

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("results", searchResults);
        responseData.put("totalFound", totalFound);
        responseData.put("totalSaved", totalSaved);

        logger.info("========================================");
        logger.info("SEARCH RESULT: found={}, saved={}, skipExisting={}, skipDupEmail={}",
                totalFound, totalSaved, totalSkippedExisting, totalSkippedDuplicateEmail);
        logger.info("========================================");

        return ResponseEntity.ok(ApiResponse.success(
                "Search completed. Found " + totalFound + " results, saved " + totalSaved + " new buyers.",
                responseData));
    }
}
