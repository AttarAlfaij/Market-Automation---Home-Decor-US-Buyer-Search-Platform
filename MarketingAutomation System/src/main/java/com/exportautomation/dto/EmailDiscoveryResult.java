package com.exportautomation.dto;

import java.util.List;

public class EmailDiscoveryResult {

    private int totalBuyersProcessed;
    private int totalEmailsFound;
    private List<String> emails;

    public EmailDiscoveryResult() {}

    public EmailDiscoveryResult(int totalBuyersProcessed, int totalEmailsFound, List<String> emails) {
        this.totalBuyersProcessed = totalBuyersProcessed;
        this.totalEmailsFound = totalEmailsFound;
        this.emails = emails;
    }

    public int getTotalBuyersProcessed() { return totalBuyersProcessed; }
    public void setTotalBuyersProcessed(int totalBuyersProcessed) { this.totalBuyersProcessed = totalBuyersProcessed; }

    public int getTotalEmailsFound() { return totalEmailsFound; }
    public void setTotalEmailsFound(int totalEmailsFound) { this.totalEmailsFound = totalEmailsFound; }

    public List<String> getEmails() { return emails; }
    public void setEmails(List<String> emails) { this.emails = emails; }
}
