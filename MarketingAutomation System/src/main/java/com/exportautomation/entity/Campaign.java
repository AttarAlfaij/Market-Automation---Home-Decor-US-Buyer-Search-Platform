package com.exportautomation.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

@Entity
@Table(name = "campaigns")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Campaign name is required")
    @Column(name = "campaign_name")
    private String campaignName;

    @NotBlank(message = "Email subject is required")
    @Column(name = "subject")
    private String emailSubject;

    @NotBlank(message = "Email body is required")
    @Column(name = "email_body", columnDefinition = "TEXT")
    private String emailBody;

    @Column(name = "target_audience")
    private String targetAudience;

    @Min(value = 1, message = "Sending limit must be at least 1")
    @Column(name = "sending_limit")
    private Integer sendingLimit;

    @Min(value = 0, message = "Delay must be 0 or more seconds")
    @Column(name = "delay_seconds")
    private Integer delayBetweenEmails;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "product_category")
    private String productCategory;

    @Column(name = "export_availability")
    private String exportAvailability;

    @Column(name = "customization_options", columnDefinition = "TEXT")
    private String customizationOptions;

    @Column(name = "minimum_order_quantity")
    private String minimumOrderQuantity;

    @Column(name = "shipping_availability")
    private String shippingAvailability;

    @NotBlank(message = "Sender name is required")
    @Column(name = "sender_name")
    private String senderName;

    @NotBlank(message = "Sender company is required")
    @Column(name = "sender_company")
    private String senderCompany;

    @NotBlank(message = "Sender email is required")
    @Email(message = "Sender email must be valid")
    @Column(name = "sender_email")
    private String senderEmail;

    @Column(name = "sender_phone")
    private String senderPhone;

    @Column(name = "attachment_name")
    private String attachmentName;

    @Column(name = "attachment_path")
    private String attachmentPath;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Campaign() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCampaignName() { return campaignName; }
    public void setCampaignName(String campaignName) { this.campaignName = campaignName; }

    public String getEmailSubject() { return emailSubject; }
    public void setEmailSubject(String emailSubject) { this.emailSubject = emailSubject; }

    public String getEmailBody() { return emailBody; }
    public void setEmailBody(String emailBody) { this.emailBody = emailBody; }

    public String getTargetAudience() { return targetAudience; }
    public void setTargetAudience(String targetAudience) { this.targetAudience = targetAudience; }

    public Integer getSendingLimit() { return sendingLimit; }
    public void setSendingLimit(Integer sendingLimit) { this.sendingLimit = sendingLimit; }

    public Integer getDelayBetweenEmails() { return delayBetweenEmails; }
    public void setDelayBetweenEmails(Integer delayBetweenEmails) { this.delayBetweenEmails = delayBetweenEmails; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getProductCategory() { return productCategory; }
    public void setProductCategory(String productCategory) { this.productCategory = productCategory; }

    public String getExportAvailability() { return exportAvailability; }
    public void setExportAvailability(String exportAvailability) { this.exportAvailability = exportAvailability; }

    public String getCustomizationOptions() { return customizationOptions; }
    public void setCustomizationOptions(String customizationOptions) { this.customizationOptions = customizationOptions; }

    public String getMinimumOrderQuantity() { return minimumOrderQuantity; }
    public void setMinimumOrderQuantity(String minimumOrderQuantity) { this.minimumOrderQuantity = minimumOrderQuantity; }

    public String getShippingAvailability() { return shippingAvailability; }
    public void setShippingAvailability(String shippingAvailability) { this.shippingAvailability = shippingAvailability; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getSenderCompany() { return senderCompany; }
    public void setSenderCompany(String senderCompany) { this.senderCompany = senderCompany; }

    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }

    public String getSenderPhone() { return senderPhone; }
    public void setSenderPhone(String senderPhone) { this.senderPhone = senderPhone; }

    public String getAttachmentName() { return attachmentName; }
    public void setAttachmentName(String attachmentName) { this.attachmentName = attachmentName; }

    public String getAttachmentPath() { return attachmentPath; }
    public void setAttachmentPath(String attachmentPath) { this.attachmentPath = attachmentPath; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
