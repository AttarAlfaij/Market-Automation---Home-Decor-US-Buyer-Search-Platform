package com.exportautomation.dto;

public class ClassificationResult {

    private String businessCategory;
    private String leadPriority;
    private String reason;

    public ClassificationResult() {}

    public ClassificationResult(String businessCategory, String leadPriority, String reason) {
        this.businessCategory = businessCategory;
        this.leadPriority = leadPriority;
        this.reason = reason;
    }

    public String getBusinessCategory() { return businessCategory; }
    public void setBusinessCategory(String businessCategory) { this.businessCategory = businessCategory; }

    public String getLeadPriority() { return leadPriority; }
    public void setLeadPriority(String leadPriority) { this.leadPriority = leadPriority; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
