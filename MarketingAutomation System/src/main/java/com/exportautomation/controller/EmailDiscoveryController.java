package com.exportautomation.controller;

import com.exportautomation.dto.ApiResponse;
import com.exportautomation.dto.EmailDiscoveryRequest;
import com.exportautomation.dto.EmailDiscoveryResult;
import com.exportautomation.service.EmailDiscoveryService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/email-discovery")
@CrossOrigin(origins = "*")
public class EmailDiscoveryController {

    private static final Logger logger = LoggerFactory.getLogger(EmailDiscoveryController.class);

    @Autowired
    private EmailDiscoveryService emailDiscoveryService;

    @PostMapping("/discover")
    public ResponseEntity<ApiResponse> discoverEmails(@Valid @RequestBody EmailDiscoveryRequest request) {
        logger.info("Email discovery request: {} buyer IDs", request.getBuyerIds().size());

        EmailDiscoveryResult result = emailDiscoveryService.discoverEmails(request.getBuyerIds());

        return ResponseEntity.ok(ApiResponse.success(
                "Email discovery completed. Found " + result.getTotalEmailsFound() + " unique emails.",
                result));
    }

    @PostMapping(value = "/download-csv", produces = "text/csv")
    public ResponseEntity<byte[]> downloadCsv(@Valid @RequestBody EmailDiscoveryRequest request) {
        logger.info("Email CSV download request: {} buyer IDs", request.getBuyerIds().size());

        EmailDiscoveryResult result = emailDiscoveryService.discoverEmails(request.getBuyerIds());
        byte[] csv = emailDiscoveryService.generateCsv(result.getEmails());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=discovered-emails.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
