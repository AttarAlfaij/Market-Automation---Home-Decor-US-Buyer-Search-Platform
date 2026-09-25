package com.exportautomation.dto;

import java.util.ArrayList;
import java.util.List;

public class SendCampaignRequest {

    private List<Recipient> recipients = new ArrayList<>();

    public List<Recipient> getRecipients() { return recipients; }
    public void setRecipients(List<Recipient> recipients) { this.recipients = recipients; }

    public static class Recipient {

        private Long buyerId;
        private String email;

        public Recipient() {}

        public Long getBuyerId() { return buyerId; }
        public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }
}
