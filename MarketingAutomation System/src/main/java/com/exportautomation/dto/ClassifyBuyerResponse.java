package com.exportautomation.dto;

public class ClassifyBuyerResponse {

    private Long buyerId;
    private String businessCategory;
    private String leadPriority;
    private String reason;

    public ClassifyBuyerResponse() {}

    public ClassifyBuyerResponse(Long buyerId, String businessCategory, String leadPriority, String reason) {
        this.buyerId = buyerId;
        this.businessCategory = businessCategory;
        this.leadPriority = leadPriority;
        this.reason = reason;
    }

    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }

    public String getBusinessCategory() { return businessCategory; }
    public void setBusinessCategory(String businessCategory) { this.businessCategory = businessCategory; }

    public String getLeadPriority() { return leadPriority; }
    public void setLeadPriority(String leadPriority) { this.leadPriority = leadPriority; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
