package com.exportautomation.dto;

public class BuyerSearchResult {

    private String companyName;
    private String rawTitle;
    private String buyerName;
    private String productName;
    // The effective product phrasing actually searched/verified (e.g. a synonym
    // like "decorative jars" for a "Decorative Jars With Handle" search). The
    // user's original productName above is what is stored/displayed.
    private String searchProduct;
    private String website;
    private String country;
    private String businessType;
    private String sourceUrl;
    private String searchQuery;
    private String snippet;
    // Raw address text from the search result itself (Firecrawl Google Business
    // data / meta address), the most reliable structured source for the buyer's
    // real location before the website is inspected.
    private String searchAddress;
    private String email;
    private String state;
    private String city;
    private String location;
    // Diagnostic: where the location was extracted from (search result, google
    // business, homepage schema, homepage footer, contact page, about page).
    private String locationSource;
    // Location-priority class used for final selection/ranking:
    // EXACT_CITY > SAME_STATE > SAME_COUNTRY ("UNKNOWN" candidates are rejected).
    private String locationClass;
    // Evidence-strength score for intra-priority ranking (requirement: prefer
    // strong product evidence, strong commercial evidence and clear business
    // identity within the same location priority).
    private int relevanceScore;
    private String classification;

    public BuyerSearchResult() {}

    public BuyerSearchResult(String companyName, String website, String country, String businessType, String sourceUrl, String searchQuery, String snippet) {
        this.companyName = companyName;
        this.rawTitle = companyName;
        this.website = website;
        this.country = country;
        this.businessType = businessType;
        this.sourceUrl = sourceUrl;
        this.searchQuery = searchQuery;
        this.snippet = snippet;
    }

    public String getRawTitle() { return rawTitle; }
    public void setRawTitle(String rawTitle) { this.rawTitle = rawTitle; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getBuyerName() { return buyerName; }
    public void setBuyerName(String buyerName) { this.buyerName = buyerName; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getSearchProduct() { return searchProduct; }
    public void setSearchProduct(String searchProduct) { this.searchProduct = searchProduct; }

    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }

    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }

    public String getSearchQuery() { return searchQuery; }
    public void setSearchQuery(String searchQuery) { this.searchQuery = searchQuery; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }

    public String getSearchAddress() { return searchAddress; }
    public void setSearchAddress(String searchAddress) { this.searchAddress = searchAddress; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getLocationSource() { return locationSource; }
    public void setLocationSource(String locationSource) { this.locationSource = locationSource; }

    public String getLocationClass() { return locationClass; }
    public void setLocationClass(String locationClass) { this.locationClass = locationClass; }

    public int getRelevanceScore() { return relevanceScore; }
    public void setRelevanceScore(int relevanceScore) { this.relevanceScore = relevanceScore; }

    public String getClassification() { return classification; }
    public void setClassification(String classification) { this.classification = classification; }
}
