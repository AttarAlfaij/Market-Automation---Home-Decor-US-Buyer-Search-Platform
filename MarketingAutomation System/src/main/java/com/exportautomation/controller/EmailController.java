package com.exportautomation.controller;

import com.exportautomation.dto.ApiResponse;
import com.exportautomation.dto.SendEmailRequest;
import com.exportautomation.dto.TestEmailRequest;
import com.exportautomation.service.EmailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/email")
@CrossOrigin(origins = "*")
public class EmailController {

    @Autowired
    private EmailService emailService;

    @PostMapping("/test")
    public ResponseEntity<ApiResponse> sendTestEmail(@Valid @RequestBody TestEmailRequest request) {
        Map<String, Object> result = emailService.sendTestEmail(request);
        return ResponseEntity.ok(ApiResponse.success("Test email sent successfully", result));
    }

    @PostMapping("/send")
    public ResponseEntity<ApiResponse> sendSingleBuyerEmail(@Valid @RequestBody SendEmailRequest request) {
        Map<String, Object> result = emailService.sendSingleBuyerEmail(request.getBuyerId(), request.getCampaignId());
        return ResponseEntity.ok(ApiResponse.success("Email sent successfully to buyer", result));
    }

    @GetMapping("/tracking-stats")
    public ResponseEntity<ApiResponse> getTrackingStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> stats = emailService.getTrackingStats(startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success("Tracking stats retrieved successfully", stats));
    }

    /**
     * Sent-email summary for an optional inclusive date range (yyyy-MM-dd).
     * Returns the total number of logged emails and the breakdown per status
     * (SENT, FAILED, ...) used by the Email Tracking summary donut chart.
     */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse> getEmailSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        Map<String, Object> summary = emailService.getEmailSummary(startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success("Email summary retrieved successfully", summary));
    }
}
