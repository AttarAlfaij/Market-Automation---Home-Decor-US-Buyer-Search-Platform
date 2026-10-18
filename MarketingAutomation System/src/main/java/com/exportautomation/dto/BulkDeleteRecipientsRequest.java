package com.exportautomation.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.ArrayList;
import java.util.List;

public class BulkDeleteRecipientsRequest {

    @NotEmpty(message = "buyerIds must not be empty")
    private List<Long> buyerIds = new ArrayList<>();

    public List<Long> getBuyerIds() { return buyerIds; }
    public void setBuyerIds(List<Long> buyerIds) { this.buyerIds = buyerIds; }
}
