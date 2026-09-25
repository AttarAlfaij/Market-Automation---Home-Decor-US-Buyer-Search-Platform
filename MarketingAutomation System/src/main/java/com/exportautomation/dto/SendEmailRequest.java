package com.exportautomation.dto;

import jakarta.validation.constraints.NotNull;

public class SendEmailRequest {

    @NotNull(message = "Buyer ID is required")
    private Long buyerId;

    @NotNull(message = "Campaign ID is required")
    private Long campaignId;

    public SendEmailRequest() {}

    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }

    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
}
