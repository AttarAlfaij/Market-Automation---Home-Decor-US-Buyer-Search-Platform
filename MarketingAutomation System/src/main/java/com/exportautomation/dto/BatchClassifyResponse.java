package com.exportautomation.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchClassifyResponse {

    private int total;
    private int classified;
    private int skipped;
    private int failed;
    private List<FailedBuyer> failures;

    public BatchClassifyResponse() {
        this.failures = new ArrayList<>();
    }

    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }

    public int getClassified() { return classified; }
    public void setClassified(int classified) { this.classified = classified; }

    public int getSkipped() { return skipped; }
    public void setSkipped(int skipped) { this.skipped = skipped; }

    public int getFailed() { return failed; }
    public void setFailed(int failed) { this.failed = failed; }

    public List<FailedBuyer> getFailures() { return failures; }
    public void setFailures(List<FailedBuyer> failures) { this.failures = failures; }

    public void addFailure(Long buyerId, String reason) {
        this.failures.add(new FailedBuyer(buyerId, reason));
    }

    public static class FailedBuyer {
        private Long buyerId;
        private String reason;

        public FailedBuyer() {}

        public FailedBuyer(Long buyerId, String reason) {
            this.buyerId = buyerId;
            this.reason = reason;
        }

        public Long getBuyerId() { return buyerId; }
        public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}
