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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

        // Snapshot the websites/emails already stored so a buyer that is already
        // in the database is NEITHER saved NOR returned as a search result.
        Set<String> knownWebsites = new HashSet<>();
        for (String website : buyerRepository.findAllWebsiteValues()) {
            String dedupKey = dedupKey(website);
            if (dedupKey != null) {
                knownWebsites.add(dedupKey);
            }
        }
        Set<String> knownEmails = new HashSet<>();
        for (String email : buyerRepository.findAllEmailValues()) {
            if (email != null && !email.isBlank()) {
                knownEmails.add(email.trim().toLowerCase(Locale.ROOT));
            }
        }

        int totalSaved = 0;
        int totalSkippedExisting = 0;
        int totalSkippedDuplicateEmail = 0;
        List<BuyerSearchResult> newResults = new ArrayList<>();

        for (BuyerSearchResult result : searchResults) {
            String dedupKey = null;
            if (result.getWebsite() != null && !result.getWebsite().isEmpty()) {
                dedupKey = dedupKey(result.getWebsite());
            }
            if (dedupKey != null && knownWebsites.contains(dedupKey)) {
                totalSkippedExisting++;
                logger.debug("SKIP '{}' - website '{}' already in database",
                        result.getCompanyName(), result.getWebsite());
                continue;
            }

            String normalizedEmail = null;
            if (result.getEmail() != null && !result.getEmail().isBlank()) {
                normalizedEmail = result.getEmail().trim().toLowerCase(Locale.ROOT);
            }
            if (normalizedEmail != null && knownEmails.contains(normalizedEmail)) {
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
                newResults.add(result);
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
        responseData.put("results", newResults);
        responseData.put("totalFound", newResults.size());
        responseData.put("totalSaved", newResults.size());
        responseData.put("totalSkippedExisting", totalSkippedExisting);
        responseData.put("totalSkippedDuplicateEmail", totalSkippedDuplicateEmail);

        logger.info("========================================");
        logger.info("SEARCH RESULT: found={}, saved={}, skipExisting={}, skipDupEmail={}",
                newResults.size(), totalSaved, totalSkippedExisting, totalSkippedDuplicateEmail);
        logger.info("========================================");

        return ResponseEntity.ok(ApiResponse.success(
                "Search completed. Found " + newResults.size() + " new results, saved "
                        + totalSaved + " new buyers.",
                responseData));
    }

    /** Dedup key for a website: the bare host for real company sites (mirrors the
     *  buyer-search service, which treats a domain as one business); the full
     *  host+path for social pages, so two different LinkedIn/Facebook pages are
     *  never merged and an already-saved social page is never returned twice. */
    private String dedupKey(String website) {
        if (website == null || website.isBlank()) {
            return null;
        }
        String value = website.trim().toLowerCase(Locale.ROOT);
        String host = null;
        String path = "";
        try {
            URI uri = URI.create(value);
            host = uri.getHost();
            if (host != null) {
                path = uri.getPath() == null ? "" : uri.getPath();
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
            }
        } catch (Exception ignored) {
            // fall through to the scheme-less fallback below
        }
        if (host == null) {
            int schemeEnd = value.indexOf("://");
            if (schemeEnd >= 0) {
                value = value.substring(schemeEnd + 3);
            }
            int slash = value.indexOf('/');
            if (slash >= 0) {
                value = value.substring(0, slash);
            }
            if (value.startsWith("www.")) {
                value = value.substring(4);
            }
            return value.isEmpty() ? null : value;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        if (host.equals("linkedin.com") || host.equals("facebook.com")) {
            host = host + path;
            return host.isEmpty() ? null : host;
        }
        return host;
    }
}
