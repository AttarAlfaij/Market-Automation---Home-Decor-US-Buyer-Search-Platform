package com.exportautomation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class BuyerSearchRequest {

    @NotBlank(message = "Product name is required")
    private String productName;

    @Min(value = 1, message = "Result limit must be at least 1")
    @Max(value = 200, message = "Result limit cannot exceed 200")
    private int resultLimit = 10;

    private String state;

    private String city;

    private String country = "USA";

    public BuyerSearchRequest() {}

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public int getResultLimit() { return resultLimit; }
    public void setResultLimit(int resultLimit) { this.resultLimit = resultLimit; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
}
