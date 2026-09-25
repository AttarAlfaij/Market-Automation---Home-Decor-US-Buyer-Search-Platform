package com.exportautomation.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class BulkCampaignResponse {

    private Long campaignId;
    private String campaignName;
    private int totalEligible;
    private int totalAttempted;
    private int totalSent;
    private int totalFailed;
    private int totalSkipped;
    private List<String> details = new ArrayList<>();
    private List<RecipientResult> results = new ArrayList<>();
    private LocalDateTime completedAt;

    public BulkCampaignResponse() {
        this.completedAt = LocalDateTime.now();
    }

    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }

    public String getCampaignName() { return campaignName; }
    public void setCampaignName(String campaignName) { this.campaignName = campaignName; }

    public int getTotalEligible() { return totalEligible; }
    public void setTotalEligible(int totalEligible) { this.totalEligible = totalEligible; }

    public int getTotalAttempted() { return totalAttempted; }
    public void setTotalAttempted(int totalAttempted) { this.totalAttempted = totalAttempted; }

    public int getTotalSent() { return totalSent; }
    public void setTotalSent(int totalSent) { this.totalSent = totalSent; }

    public int getTotalFailed() { return totalFailed; }
    public void setTotalFailed(int totalFailed) { this.totalFailed = totalFailed; }

    public int getTotalSkipped() { return totalSkipped; }
    public void setTotalSkipped(int totalSkipped) { this.totalSkipped = totalSkipped; }

    public List<String> getDetails() { return details; }
    public void setDetails(List<String> details) { this.details = details; }

    public List<RecipientResult> getResults() { return results; }
    public void setResults(List<RecipientResult> results) { this.results = results; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
