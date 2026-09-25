package com.exportautomation.controller;

import com.exportautomation.dto.ApiResponse;
import com.exportautomation.dto.LeadRequest;
import com.exportautomation.entity.CampaignResponse;
import com.exportautomation.entity.Lead;
import com.exportautomation.service.LeadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/leads")
@CrossOrigin(origins = "*")
public class LeadController {

    @Autowired
    private LeadService leadService;

    @GetMapping
    public ResponseEntity<ApiResponse> getAllLeads() {
        List<Lead> leads = leadService.getAllLeads();
        return ResponseEntity.ok(ApiResponse.success("Leads retrieved successfully", leads));
    }

    @PostMapping
    public ResponseEntity<ApiResponse> addLead(@RequestBody LeadRequest request) {
        Lead lead = leadService.addLead(request);
        return new ResponseEntity<>(ApiResponse.success("Lead added successfully", lead), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse> updateLead(@PathVariable Long id, @RequestBody LeadRequest request) {
        Lead lead = leadService.updateLead(id, request);
        return ResponseEntity.ok(ApiResponse.success("Lead updated successfully", lead));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteLead(@PathVariable Long id) {
        leadService.deleteLead(id);
        return ResponseEntity.ok(ApiResponse.success("Lead deleted successfully", null));
    }

    @GetMapping("/responses")
    public ResponseEntity<ApiResponse> getAllResponses() {
        List<CampaignResponse> responses = leadService.getAllResponses();
        return ResponseEntity.ok(ApiResponse.success("Responses retrieved successfully", responses));
    }

    @PostMapping("/responses")
    public ResponseEntity<ApiResponse> addResponse(@RequestBody CampaignResponse response) {
        CampaignResponse saved = leadService.addResponse(response);
        if (saved == null) {
            return ResponseEntity.ok(ApiResponse.success("Duplicate response - already recorded", null));
        }
        return new ResponseEntity<>(ApiResponse.success("Response recorded successfully", saved), HttpStatus.CREATED);
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse> getStats() {
        Map<String, Object> stats = leadService.getLeadsAndResponsesStats();
        return ResponseEntity.ok(ApiResponse.success("Stats retrieved successfully", stats));
    }

    @PostMapping("/check-replies")
    public ResponseEntity<ApiResponse> checkForReplies() {
        if (!leadService.isImapEnabled()) {
            Map<String, Object> info = new HashMap<>();
            info.put("imapEnabled", false);
            info.put("message", "IMAP reply detection is not configured. Enable it in application.properties by setting mail.imap.enabled=true, mail.imap.host, mail.imap.username, and mail.imap.password.");
            return ResponseEntity.ok(ApiResponse.success("IMAP not configured", info));
        }
        int detected = leadService.checkForReplies();
        Map<String, Object> result = new HashMap<>();
        result.put("imapEnabled", true);
        result.put("detected", detected);
        result.put("message", detected > 0 ? "Found " + detected + " new response(s)" : "No new responses found");
        return ResponseEntity.ok(ApiResponse.success("Reply check completed", result));
    }

    @GetMapping("/imap-status")
    public ResponseEntity<ApiResponse> getImapStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", leadService.isImapEnabled());
        return ResponseEntity.ok(ApiResponse.success("IMAP status retrieved", status));
    }
}
