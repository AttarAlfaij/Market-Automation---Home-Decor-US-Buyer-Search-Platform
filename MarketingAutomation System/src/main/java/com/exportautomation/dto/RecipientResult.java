package com.exportautomation.dto;

import com.exportautomation.entity.Buyer;

public class RecipientResult {

    private Long buyerId;
    private String buyerName;
    private String companyName;
    private String sourceUrl;
    private String email;
    private String status;
    private String message;

    public RecipientResult() {}

    public static RecipientResult of(Long buyerId, String buyerName, String email, String status, String message) {
        RecipientResult r = new RecipientResult();
        r.setBuyerId(buyerId);
        r.setBuyerName(buyerName);
        r.setEmail(email);
        r.setStatus(status);
        r.setMessage(message);
        return r;
    }

    public static RecipientResult of(Buyer buyer, String email, String status, String message) {
        RecipientResult r = new RecipientResult();
        r.setBuyerId(buyer.getId());
        r.setBuyerName(buyer.getBuyerName());
        r.setCompanyName(buyer.getCompanyName());
        r.setSourceUrl(buyer.getSourceUrl() != null && !buyer.getSourceUrl().isBlank()
                ? buyer.getSourceUrl() : buyer.getWebsite());
        r.setEmail(email);
        r.setStatus(status);
        r.setMessage(message);
        return r;
    }

    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
