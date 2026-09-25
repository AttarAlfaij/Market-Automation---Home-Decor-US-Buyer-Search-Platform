package com.exportautomation.dto;

public class CampaignRecipient {

    private Long buyerId;
    private String buyerName;
    private String companyName;
    private String email;
    private String country;
    private String website;
    private String sourceUrl;
    private String sourcePlatform;
    private String emailStatus;
    private boolean alreadySent;

    public CampaignRecipient() {}

    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getSourcePlatform() { return sourcePlatform; }
    public void setSourcePlatform(String sourcePlatform) { this.sourcePlatform = sourcePlatform; }

    public String getEmailStatus() { return emailStatus; }
    public void setEmailStatus(String emailStatus) { this.emailStatus = emailStatus; }

    public boolean isAlreadySent() { return alreadySent; }
    public void setAlreadySent(boolean alreadySent) { this.alreadySent = alreadySent; }
}
