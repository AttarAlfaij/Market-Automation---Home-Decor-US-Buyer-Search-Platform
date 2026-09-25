package com.exportautomation.controller;

import com.exportautomation.dto.ApiResponse;
import com.exportautomation.dto.BulkCampaignResponse;
import com.exportautomation.dto.CampaignRecipient;
import com.exportautomation.dto.SendCampaignRequest;
import com.exportautomation.entity.Campaign;
import com.exportautomation.service.CampaignService;
import com.exportautomation.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/campaigns")
@CrossOrigin(origins = "*")
public class CampaignController {

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Validator validator;

    @GetMapping
    public ResponseEntity<ApiResponse> getAllCampaigns() {
        List<Campaign> campaigns = campaignService.getAllCampaigns();
        return ResponseEntity.ok(ApiResponse.success("Campaigns retrieved successfully", campaigns));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse> getCampaignById(@PathVariable Long id) {
        Campaign campaign = campaignService.getCampaignById(id);
        return ResponseEntity.ok(ApiResponse.success("Campaign retrieved successfully", campaign));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> createCampaign(
            @RequestPart("campaign") String campaignJson,
            @RequestPart(value = "file", required = false) MultipartFile file) throws Exception {
        Campaign campaign = parseAndValidateCampaign(campaignJson);
        Campaign created = campaignService.createCampaign(campaign, file);
        return new ResponseEntity<>(ApiResponse.success("Campaign created successfully", created), HttpStatus.CREATED);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> updateCampaign(
            @PathVariable Long id,
            @RequestPart("campaign") String campaignJson,
            @RequestPart(value = "file", required = false) MultipartFile file) throws Exception {
        Campaign campaign = parseAndValidateCampaign(campaignJson);
        Campaign updated = campaignService.updateCampaign(id, campaign, file);
        return ResponseEntity.ok(ApiResponse.success("Campaign updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteCampaign(@PathVariable Long id) {
        campaignService.deleteCampaign(id);
        return ResponseEntity.ok(ApiResponse.success("Campaign deleted successfully", null));
    }

    @PostMapping("/{campaignId}/send/{buyerId}")
    public ResponseEntity<ApiResponse> sendEmail(
            @PathVariable Long campaignId,
            @PathVariable Long buyerId) {
        Map<String, Object> result = emailService.sendSingleBuyerEmail(buyerId, campaignId);
        return ResponseEntity.ok(ApiResponse.success("Email sent successfully", result));
    }

    @PostMapping("/{campaignId}/send-all")
    public ResponseEntity<ApiResponse> sendCampaignToAll(@PathVariable Long campaignId) {
        BulkCampaignResponse result = emailService.sendCampaignToEligibleBuyers(campaignId);
        return ResponseEntity.ok(ApiResponse.success("Campaign bulk send completed", result));
    }

    @GetMapping("/{campaignId}/recipients")
    public ResponseEntity<ApiResponse> getCampaignRecipients(@PathVariable Long campaignId) {
        List<CampaignRecipient> recipients = emailService.getCampaignRecipients(campaignId);
        return ResponseEntity.ok(ApiResponse.success("Campaign recipients retrieved successfully", recipients));
    }

    @PutMapping("/{campaignId}/recipients/{buyerId}")
    public ResponseEntity<ApiResponse> updateRecipientEmail(
            @PathVariable Long campaignId,
            @PathVariable Long buyerId,
            @RequestBody Map<String, String> body) {
        String email = body.get("email");
        CampaignRecipient updated = emailService.updateRecipientEmail(campaignId, buyerId, email);
        return ResponseEntity.ok(ApiResponse.success("Recipient email updated successfully", updated));
    }

    @PostMapping("/{campaignId}/send")
    public ResponseEntity<ApiResponse> sendCampaignFromFrontend(
            @PathVariable Long campaignId,
            @RequestBody SendCampaignRequest request) {
        BulkCampaignResponse ack = emailService.startCampaignSend(campaignId, request);
        return ResponseEntity.ok(ApiResponse.success("Campaign send started", ack));
    }

    @GetMapping("/{campaignId}/send-status")
    public ResponseEntity<ApiResponse> getCampaignSendStatus(@PathVariable Long campaignId) {
        Map<String, Object> status = emailService.getSendJobStatus(campaignId);
        return ResponseEntity.ok(ApiResponse.success("Campaign send status retrieved successfully", status));
    }

    @DeleteMapping("/{campaignId}/recipients/{buyerId}")
    public ResponseEntity<ApiResponse> deleteRecipient(
            @PathVariable Long campaignId,
            @PathVariable Long buyerId) {
        emailService.removeRecipientFromCampaign(campaignId, buyerId);
        return ResponseEntity.ok(ApiResponse.success("Buyer deleted from campaign successfully", null));
    }

    private Campaign parseAndValidateCampaign(String campaignJson) {
        try {
            Campaign campaign = objectMapper.readValue(campaignJson, Campaign.class);
            Set<ConstraintViolation<Campaign>> violations = validator.validate(campaign);
            if (!violations.isEmpty()) {
                String errors = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException("Validation failed: " + errors);
            }
            return campaign;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid campaign JSON: " + e.getMessage());
        }
    }
}
