package com.exportautomation.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public class EmailDiscoveryRequest {

    @NotEmpty(message = "At least one buyer ID is required")
    private List<Long> buyerIds;

    public EmailDiscoveryRequest() {}

    public List<Long> getBuyerIds() { return buyerIds; }
    public void setBuyerIds(List<Long> buyerIds) { this.buyerIds = buyerIds; }
}
