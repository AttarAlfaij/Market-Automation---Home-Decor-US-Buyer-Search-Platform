package com.exportautomation.service;

import com.exportautomation.dto.BulkCampaignResponse;
import com.exportautomation.dto.CampaignRecipient;
import com.exportautomation.dto.RecipientResult;
import com.exportautomation.dto.SendCampaignRequest;
import com.exportautomation.dto.TestEmailRequest;
import com.exportautomation.entity.Buyer;
import com.exportautomation.entity.Campaign;
import com.exportautomation.entity.CampaignRecipientExclusion;
import com.exportautomation.entity.EmailLog;
import com.exportautomation.exception.ResourceNotFoundException;
import com.exportautomation.repository.BuyerRepository;
import com.exportautomation.repository.CampaignRecipientExclusionRepository;
import com.exportautomation.repository.CampaignRepository;
import com.exportautomation.repository.EmailLogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    @Autowired
    private BuyerRepository buyerRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private CampaignRecipientExclusionRepository exclusionRepository;

    @Autowired
    private com.exportautomation.repository.CampaignResponseRepository responseRepository;

    @Value("${campaign.upload.dir:uploads/campaign-attachments}")
    private String uploadDir;

    @Value("${mail.from:}")
    private String mailFrom;

    @Value("${spring.mail.username:}")
    private String smtpUsername;

    private final ConcurrentHashMap<Long, Boolean> activeCampaigns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, SendJob> sendJobs = new ConcurrentHashMap<>();

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    @PostConstruct
    public void validateSmtpConfiguration() {
        logger.info("====================================================================");
        logger.info("SMTP CONFIGURATION VALIDATION");
        logger.info("====================================================================");

        boolean hasErrors = false;

        // Check that App Password is configured
        if (smtpUsername == null || smtpUsername.isBlank()) {
            logger.error("SMTP CONFIG ERROR: spring.mail.username is not set. Set the GMAIL_USERNAME environment variable.");
            hasErrors = true;
        } else {
            logger.info("SMTP username (authenticated account): {}", smtpUsername);
        }

        String appPassword = System.getenv("GMAIL_APP_PASSWORD");
        if (appPassword == null || appPassword.isBlank()) {
            logger.error("SMTP CONFIG ERROR: GMAIL_APP_PASSWORD environment variable is not set. "
                    + "Generate an App Password at https://myaccount.google.com/apppasswords "
                    + "and set it as the GMAIL_APP_PASSWORD environment variable.");
            hasErrors = true;
        } else {
            logger.info("SMTP App Password: [configured, {} chars]", appPassword.length());
        }

        // Validate sender email matches authenticated account
        String effectiveFrom = (mailFrom != null && !mailFrom.isBlank()) ? mailFrom : smtpUsername;
        if (effectiveFrom != null && smtpUsername != null
                && !effectiveFrom.equalsIgnoreCase(smtpUsername)) {
            logger.error("SMTP CONFIG ERROR: mail.from ('{}') does NOT match spring.mail.username ('{}'). "
                    + "Gmail requires the sender email to match the authenticated account. "
                    + "Set the GMAIL_USERNAME environment variable to '{}', or update mail.from to '{}'.",
                    effectiveFrom, smtpUsername, effectiveFrom, smtpUsername);
            hasErrors = true;
        } else {
            logger.info("Sender email (mail.from): {} — matches authenticated account", effectiveFrom);
        }

        logger.info("SMTP host: smtp.gmail.com, port: 587, STARTTLS: required, auth: true");
        logger.info("====================================================================");

        if (!hasErrors) {
            // Test SMTP connection
            try {
                MimeMessage testMessage = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(testMessage);
                helper.setFrom(effectiveFrom);
                helper.setTo(smtpUsername);
                helper.setSubject("SMTP Configuration Test");
                helper.setText("This is a configuration test email. You can ignore this.");
                mailSender.send(testMessage);
                logger.info("SMTP CONNECTION TEST: SUCCESS — sent test email to {}", smtpUsername);
            } catch (Exception e) {
                String rootCause = getRootCauseMessage(e);
                logger.error("SMTP CONNECTION TEST: FAILED — {}", rootCause);
                logger.error("SMTP CONNECTION TEST: Full exception:", e);
                logger.error("Common causes for Gmail authentication failure:");
                logger.error("  1. GMAIL_APP_PASSWORD is incorrect or empty");
                logger.error("  2. 2-Factor Authentication is not enabled on the Gmail account (required for App Passwords)");
                logger.error("  3. The App Password was revoked or expired");
                logger.error("  4. Generate a new App Password at: https://myaccount.google.com/apppasswords");
            }
        } else {
            logger.warn("Skipping SMTP connection test due to configuration errors above.");
        }

        logger.info("====================================================================");
    }

    public Map<String, Object> sendTestEmail(TestEmailRequest request) {
        Campaign campaign = campaignRepository.findById(request.getCampaignId())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + request.getCampaignId()));

        String fromAddress = (mailFrom != null && !mailFrom.isBlank()) ? mailFrom : campaign.getSenderEmail();
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new RuntimeException("Configuration error: mail.from property is not set and campaign has no sender email. Set MAIL_FROM environment variable.");
        }

        String subject = personalizeText(campaign.getEmailSubject(), request.getBuyerName(), request.getCompanyName());
        String body = personalizeText(campaign.getEmailBody(), request.getBuyerName(), request.getCompanyName());

        EmailLog emailLog = new EmailLog();
        emailLog.setCampaignId(campaign.getId());
        emailLog.setEmail(request.getRecipientEmail());
        emailLog.setSubject(subject);

        try {
            if (smtpUsername != null && !smtpUsername.isBlank()
                    && fromAddress != null && !fromAddress.equalsIgnoreCase(smtpUsername)) {
                logger.warn("Test email sender '{}' does not match authenticated Gmail account '{}'. "
                        + "Gmail may reject this. Overriding sender to '{}'.",
                        fromAddress, smtpUsername, smtpUsername);
                fromAddress = smtpUsername;
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(fromAddress);
            helper.setTo(request.getRecipientEmail());
            helper.setSubject(subject);
            helper.setText(ensureHtmlBody(body), true);

            if (campaign.getAttachmentPath() != null && !campaign.getAttachmentPath().isEmpty()) {
                Path attachmentPath = Paths.get(campaign.getAttachmentPath());
                if (Files.exists(attachmentPath)) {
                    FileSystemResource resource = new FileSystemResource(attachmentPath.toFile());
                    String attachmentName = campaign.getAttachmentName() != null
                            ? campaign.getAttachmentName()
                            : attachmentPath.getFileName().toString();
                    helper.addAttachment(attachmentName, resource);
                } else {
                    logger.warn("Attachment file not found at: {}", campaign.getAttachmentPath());
                }
            }

            mailSender.send(message);

            emailLog.setStatus("SENT");
            emailLog.setSentAt(LocalDateTime.now());
            emailLogRepository.save(emailLog);

            Map<String, Object> result = new HashMap<>();
            result.put("recipientEmail", request.getRecipientEmail());
            result.put("campaignId", campaign.getId());
            result.put("campaignName", campaign.getCampaignName());
            result.put("subject", subject);
            result.put("attachmentIncluded", campaign.getAttachmentPath() != null
                    && Files.exists(Paths.get(campaign.getAttachmentPath())));
            return result;

        } catch (MessagingException e) {
            String rootCause = getRootCauseMessage(e);
            logger.error("Failed to send test email to {} — SMTP root cause: {}", request.getRecipientEmail(), rootCause, e);
            emailLog.setStatus("FAILED");
            emailLog.setSentAt(LocalDateTime.now());
            emailLog.setErrorMessage("SMTP error: " + rootCause);
            emailLogRepository.save(emailLog);
            throw new RuntimeException("Failed to send email (SMTP error): " + rootCause, e);
        } catch (Exception e) {
            String rootCause = getRootCauseMessage(e);
            logger.error("Unexpected error sending test email to {} — root cause: {}", request.getRecipientEmail(), rootCause, e);
            emailLog.setStatus("FAILED");
            emailLog.setSentAt(LocalDateTime.now());
            emailLog.setErrorMessage("Error: " + rootCause);
            emailLogRepository.save(emailLog);
            throw new RuntimeException("Failed to send email: " + rootCause, e);
        }
    }

    public Map<String, Object> sendSingleBuyerEmail(Long buyerId, Long campaignId) {
        Buyer buyer = buyerRepository.findById(buyerId)
                .orElseThrow(() -> new ResourceNotFoundException("Buyer not found with id: " + buyerId));

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + campaignId));

        if (buyer.getEmail() == null || buyer.getEmail().isBlank()) {
            throw new IllegalArgumentException("Buyer id=" + buyerId + " does not have a valid email address.");
        }

        if (emailLogRepository.existsByBuyerIdAndCampaignIdAndStatus(buyerId, campaignId, "SENT")) {
            throw new IllegalArgumentException("Buyer id=" + buyerId + " has already been sent campaign " + campaignId + ". Duplicate prevention: skipping.");
        }

        String fromAddress = (mailFrom != null && !mailFrom.isBlank()) ? mailFrom : campaign.getSenderEmail();
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new RuntimeException("Configuration error: mail.from property is not set and campaign has no sender email.");
        }

        String productName = buyer.getProductName() != null ? buyer.getProductName()
                : (campaign.getProductName() != null ? campaign.getProductName() : "");

        String subject = personalizeText(campaign.getEmailSubject(), buyer.getBuyerName(), buyer.getCompanyName(), productName);
        String body = personalizeText(campaign.getEmailBody(), buyer.getBuyerName(), buyer.getCompanyName(), productName);

        EmailLog emailLog = new EmailLog();
        emailLog.setBuyerId(buyer.getId());
        emailLog.setCampaignId(campaign.getId());
        emailLog.setEmail(buyer.getEmail());
        emailLog.setSubject(subject);

        try {
            if (smtpUsername != null && !smtpUsername.isBlank()
                    && fromAddress != null && !fromAddress.equalsIgnoreCase(smtpUsername)) {
                logger.warn("Buyer email sender '{}' does not match authenticated Gmail account '{}'. "
                        + "Gmail may reject this. Overriding sender to '{}'.",
                        fromAddress, smtpUsername, smtpUsername);
                fromAddress = smtpUsername;
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            helper.setFrom(fromAddress);
            helper.setTo(buyer.getEmail());
            helper.setSubject(subject);
            helper.setText(ensureHtmlBody(body), true);

            if (campaign.getAttachmentPath() != null && !campaign.getAttachmentPath().isEmpty()) {
                Path attachmentPath = Paths.get(campaign.getAttachmentPath());
                if (Files.exists(attachmentPath)) {
                    FileSystemResource resource = new FileSystemResource(attachmentPath.toFile());
                    String attachmentName = campaign.getAttachmentName() != null
                            ? campaign.getAttachmentName()
                            : attachmentPath.getFileName().toString();
                    helper.addAttachment(attachmentName, resource);
                } else {
                    logger.warn("Attachment file not found at: {}", campaign.getAttachmentPath());
                }
            }

            mailSender.send(message);

            emailLog.setStatus("SENT");
            emailLog.setSentAt(LocalDateTime.now());
            emailLog.setMessageId(readMessageId(message));
            emailLogRepository.save(emailLog);

            buyer.setEmailStatus("CONTACTED");
            buyer.setContactedAt(LocalDateTime.now());
            buyerRepository.save(buyer);

            Map<String, Object> result = new HashMap<>();
            result.put("buyerId", buyer.getId());
            result.put("buyerName", buyer.getBuyerName());
            result.put("buyerEmail", buyer.getEmail());
            result.put("campaignId", campaign.getId());
            result.put("campaignName", campaign.getCampaignName());
            result.put("subject", subject);
            result.put("attachmentIncluded", campaign.getAttachmentPath() != null
                    && Files.exists(Paths.get(campaign.getAttachmentPath())));
            return result;

        } catch (MessagingException e) {
            String rootCause = getRootCauseMessage(e);
            logger.error("Failed to send email to {} — SMTP root cause: {}", buyer.getEmail(), rootCause, e);
            emailLog.setStatus("FAILED");
            emailLog.setSentAt(LocalDateTime.now());
            emailLog.setErrorMessage("SMTP error: " + rootCause);
            emailLogRepository.save(emailLog);
            throw new RuntimeException("Failed to send email (SMTP error): " + rootCause, e);
        } catch (Exception e) {
            String rootCause = getRootCauseMessage(e);
            logger.error("Unexpected error sending email to {} — root cause: {}", buyer.getEmail(), rootCause, e);
            emailLog.setStatus("FAILED");
            emailLog.setSentAt(LocalDateTime.now());
            emailLog.setErrorMessage("Error: " + rootCause);
            emailLogRepository.save(emailLog);
            throw new RuntimeException("Failed to send email: " + rootCause, e);
        }
    }

    private String ensureHtmlBody(String body) {
        if (body == null || body.isBlank()) return body;
        if (body.contains("<") && body.contains(">")) return body;
        return "<p style=\"margin:0 0 12px 0;\">"
                + body.replace("\r\n", "\n").replace("\n", "<br />")
                + "</p>";
    }

    private String getRootCauseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    private String personalizeText(String template, String buyerName, String companyName) {
        if (template == null) return "";
        return template
                .replace("{{buyerName}}", buyerName != null ? buyerName : "")
                .replace("{{companyName}}", companyName != null ? companyName : "");    }

    private String personalizeText(String template, String buyerName, String companyName, String productName) {
        if (template == null) return "";
        return template
                .replace("{{buyerName}}", buyerName != null ? buyerName : "")
                .replace("{{companyName}}", companyName != null ? companyName : "")
                .replace("{{productName}}", productName != null ? productName : "");
    }

    private String personalizeTextFull(String template, String buyerName, String companyName, String productName, String country) {
        if (template == null) return "";
        return template
                .replace("{{buyerName}}", buyerName != null ? buyerName : "")
                .replace("{{companyName}}", companyName != null ? companyName : "")
                .replace("{{productName}}", productName != null ? productName : "")
                .replace("{{country}}", country != null ? country : "");
    }

    public BulkCampaignResponse sendCampaignToEligibleBuyers(Long campaignId) {
        logger.info("====================================================================");
        logger.info("[Campaign {}] BULK SEND REQUESTED", campaignId);

        Boolean alreadyActive = activeCampaigns.putIfAbsent(campaignId, Boolean.TRUE);
        if (alreadyActive != null) {
            logger.warn("[Campaign {}] REJECTED - campaign is already being processed by another request", campaignId);
            BulkCampaignResponse rejection = new BulkCampaignResponse();
            rejection.setCampaignId(campaignId);
            rejection.setCampaignName("N/A");
            rejection.setDetails(List.of("REJECTED: Campaign " + campaignId + " is already being processed. Please wait for the current execution to finish."));
            return rejection;
        }

        try {
            Campaign campaign = campaignRepository.findById(campaignId)
                    .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + campaignId));

            String fromAddress = (mailFrom != null && !mailFrom.isBlank()) ? mailFrom : campaign.getSenderEmail();
            if (fromAddress == null || fromAddress.isBlank()) {
                throw new RuntimeException("Configuration error: mail.from property is not set and campaign has no sender email.");
            }

            BulkCampaignResponse summary = new BulkCampaignResponse();
            summary.setCampaignId(campaign.getId());
            summary.setCampaignName(campaign.getCampaignName());

            int sendingLimit = (campaign.getSendingLimit() != null && campaign.getSendingLimit() > 0)
                    ? campaign.getSendingLimit() : Integer.MAX_VALUE;
            logger.info("[Campaign {}] Campaign name: {}", campaignId, campaign.getCampaignName());
            logger.info("[Campaign {}] Hard sending limit: {}", campaignId, sendingLimit == Integer.MAX_VALUE ? "UNLIMITED" : sendingLimit);
            logger.info("[Campaign {}] Delay between emails: {}s", campaignId,
                    (campaign.getDelayBetweenEmails() != null && campaign.getDelayBetweenEmails() > 0) ? campaign.getDelayBetweenEmails() : 5);

            List<Buyer> allBuyers = buyerRepository.findAll();
            logger.info("[Campaign {}] Total buyers in database: {}", campaignId, allBuyers.size());

            List<Buyer> eligibleBuyers = new ArrayList<>();
            for (Buyer b : allBuyers) {
                if (b.getEmail() == null || b.getEmail().isBlank()) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    String detail = "SKIPPED buyerId=" + b.getId() + " " + b.getBuyerName() + " - no valid email";
                    summary.getDetails().add(detail);
                    logger.info("[Campaign {}] {} reason=NO_EMAIL", campaignId, detail);
                    continue;
                }
                if (emailLogRepository.existsByBuyerIdAndCampaignIdAndStatus(b.getId(), campaignId, "SENT")) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    String detail = "SKIPPED buyerId=" + b.getId() + " " + b.getEmail() + " - already SENT for campaign " + campaignId;
                    summary.getDetails().add(detail);
                    logger.info("[Campaign {}] {} reason=ALREADY_SENT", campaignId, detail);
                    continue;
                }
                eligibleBuyers.add(b);
            }

            logger.info("[Campaign {}] Eligible buyers after duplicate/no-email filtering: {}", campaignId, eligibleBuyers.size());

            if (eligibleBuyers.size() > sendingLimit) {
                eligibleBuyers = new ArrayList<>(eligibleBuyers.subList(0, sendingLimit));
                logger.info("[Campaign {}] Trimmed to {} buyers (HARD sending limit applied)", campaignId, sendingLimit);
            } else {
                logger.info("[Campaign {}] All {} eligible buyers within sending limit", campaignId, eligibleBuyers.size());
            }

            summary.setTotalEligible(eligibleBuyers.size());

            int delaySeconds = (campaign.getDelayBetweenEmails() != null && campaign.getDelayBetweenEmails() > 0)
                    ? campaign.getDelayBetweenEmails() : 5;

            for (int i = 0; i < eligibleBuyers.size(); i++) {
                Buyer buyer = eligibleBuyers.get(i);
                int attemptNumber = i + 1;

                if (emailLogRepository.existsByBuyerIdAndCampaignIdAndStatus(buyer.getId(), campaignId, "SENT")) {
                    summary.setTotalAttempted(summary.getTotalAttempted() - 1);
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    String detail = "SKIPPED buyerId=" + buyer.getId() + " " + buyer.getEmail() + " - already SENT (re-check before send)";
                    summary.getDetails().add(detail);
                    logger.info("[Campaign {}] {} attempt={}/{} reason=ALREADY_SENT_RECHECK", campaignId, detail, attemptNumber, eligibleBuyers.size());
                    continue;
                }

                summary.setTotalAttempted(summary.getTotalAttempted() + 1);

                String productName = buyer.getProductName() != null ? buyer.getProductName()
                        : (campaign.getProductName() != null ? campaign.getProductName() : "");

                String subject = personalizeTextFull(campaign.getEmailSubject(), buyer.getBuyerName(), buyer.getCompanyName(), productName, buyer.getCountry());
                String body = personalizeTextFull(campaign.getEmailBody(), buyer.getBuyerName(), buyer.getCompanyName(), productName, buyer.getCountry());

                logger.info("[Campaign {}] ATTEMPT {}/{} buyerId={} email=\"{}\" subject=\"{}\" - SENDING",
                        campaignId, attemptNumber, eligibleBuyers.size(), buyer.getId(), buyer.getEmail(), subject);

                EmailLog emailLog = new EmailLog();
                emailLog.setBuyerId(buyer.getId());
                emailLog.setCampaignId(campaign.getId());
                emailLog.setEmail(buyer.getEmail());
                emailLog.setSubject(subject);

                try {
                    if (smtpUsername != null && !smtpUsername.isBlank()
                            && fromAddress != null && !fromAddress.equalsIgnoreCase(smtpUsername)) {
                        logger.warn("[Campaign {}] Sender '{}' does not match authenticated Gmail account '{}'. Overriding.",
                                campaignId, fromAddress, smtpUsername);
                        fromAddress = smtpUsername;
                    }

                    MimeMessage message = mailSender.createMimeMessage();
                    MimeMessageHelper helper = new MimeMessageHelper(message, true);
                    helper.setFrom(fromAddress);
                    helper.setTo(buyer.getEmail());
                    helper.setSubject(subject);
                    helper.setText(ensureHtmlBody(body), true);

                    if (campaign.getAttachmentPath() != null && !campaign.getAttachmentPath().isEmpty()) {
                        Path attachmentPath = Paths.get(campaign.getAttachmentPath());
                        if (Files.exists(attachmentPath)) {
                            FileSystemResource resource = new FileSystemResource(attachmentPath.toFile());
                            String attachmentName = campaign.getAttachmentName() != null
                                    ? campaign.getAttachmentName()
                                    : attachmentPath.getFileName().toString();
                            helper.addAttachment(attachmentName, resource);
                        } else {
                            logger.warn("[Campaign {}] Attachment file not found at: {}", campaignId, campaign.getAttachmentPath());
                        }
                    }

                    mailSender.send(message);

                    emailLog.setStatus("SENT");
                    emailLog.setSentAt(LocalDateTime.now());
                    emailLog.setMessageId(readMessageId(message));
                    emailLogRepository.save(emailLog);

                    buyer.setEmailStatus("CONTACTED");
                    buyer.setContactedAt(LocalDateTime.now());
                    buyerRepository.save(buyer);

                    summary.setTotalSent(summary.getTotalSent() + 1);
                    String detail = "SENT buyerId=" + buyer.getId() + " " + buyer.getEmail() + " subject=\"" + subject + "\"";
                    summary.getDetails().add(detail);
                    logger.info("[Campaign {}] {} attempt={}/{} status=SENT sentAt={}", campaignId, detail, attemptNumber, eligibleBuyers.size(), LocalDateTime.now());

                } catch (Exception e) {
                    String rootCause = getRootCauseMessage(e);
                    logger.error("[Campaign {}] FAILED buyerId={} email=\"{}\" attempt={}/{} SMTP root cause: \"{}\"",
                            campaignId, buyer.getId(), buyer.getEmail(), attemptNumber, eligibleBuyers.size(), rootCause, e);
                    emailLog.setStatus("FAILED");
                    emailLog.setSentAt(LocalDateTime.now());
                    emailLog.setErrorMessage("SMTP error: " + rootCause);
                    emailLogRepository.save(emailLog);

                    summary.setTotalFailed(summary.getTotalFailed() + 1);
                    String detail = "FAILED buyerId=" + buyer.getId() + " " + buyer.getEmail() + " error=" + rootCause;
                    summary.getDetails().add(detail);
                }

                if (i < eligibleBuyers.size() - 1) {
                    try {
                        Thread.sleep(delaySeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("[Campaign {}] INTERRUPTED after buyerId={} attempt={}", campaignId, buyer.getId(), attemptNumber);
                        summary.getDetails().add("INTERRUPTED after buyerId=" + buyer.getId() + " attempt=" + attemptNumber);
                        break;
                    }
                }
            }

            logger.info("====================================================================");
            logger.info("[Campaign {}] BULK SEND COMPLETE: eligible={} attempted={} sent={} failed={} skipped={}",
                    campaignId, summary.getTotalEligible(), summary.getTotalAttempted(),
                    summary.getTotalSent(), summary.getTotalFailed(), summary.getTotalSkipped());
            logger.info("====================================================================");

            return summary;

        } finally {
            activeCampaigns.remove(campaignId);
            logger.info("[Campaign {}] Campaign lock released", campaignId);
        }
    }

    // ── Frontend-driven review & send flow ──────────────────────────────────────

    public List<CampaignRecipient> getCampaignRecipients(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + campaignId));

        List<CampaignRecipient> recipients = new ArrayList<>();
        Set<Long> excludedBuyerIds = new HashSet<>(exclusionRepository.findBuyerIdsByCampaignId(campaign.getId()));
        for (Buyer b : buyerRepository.findAll()) {
            if (excludedBuyerIds.contains(b.getId())) {
                continue;
            }
            CampaignRecipient r = new CampaignRecipient();
            r.setBuyerId(b.getId());
            r.setBuyerName(b.getBuyerName());
            r.setCompanyName(b.getCompanyName());
            r.setEmail(b.getEmail());
            r.setCountry(b.getCountry());
            r.setWebsite(b.getWebsite());
            r.setSourceUrl(b.getSourceUrl());
            r.setSourcePlatform(b.getSourcePlatform());
            r.setEmailStatus(b.getEmailStatus());
            // A buyer emailed through ANY campaign is shown as SENT in every
            // campaign, so a status reflects the mail's true state globally
            // instead of only inside the campaign that sent it.
            r.setAlreadySent(b.getEmail() != null && !b.getEmail().isBlank()
                    && emailLogRepository.existsByBuyerIdAndStatus(b.getId(), "SENT"));
            recipients.add(r);
        }
        logger.info("[Campaign {}] Retrieved {} recipients for review", campaignId, recipients.size());
        return recipients;
    }

    public CampaignRecipient updateRecipientEmail(Long campaignId, Long buyerId, String email) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + campaignId));

        Buyer buyer = buyerRepository.findById(buyerId)
                .orElseThrow(() -> new ResourceNotFoundException("Buyer not found with id: " + buyerId));

        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email is required");
        }
        String normalized = email.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid email format: " + normalized);
        }
        if (buyerRepository.existsByEmailIgnoreCaseAndIdNot(normalized, buyerId)) {
            throw new IllegalArgumentException("Another buyer already uses this email: " + normalized);
        }

        buyer.setEmail(normalized);
        buyerRepository.save(buyer);
        logger.info("[Campaign {}] Recipient buyerId={} email updated to {}", campaignId, buyerId, normalized);

        CampaignRecipient r = new CampaignRecipient();
        r.setBuyerId(buyer.getId());
        r.setBuyerName(buyer.getBuyerName());
        r.setCompanyName(buyer.getCompanyName());
        r.setEmail(buyer.getEmail());
        r.setCountry(buyer.getCountry());
        r.setWebsite(buyer.getWebsite());
        r.setSourceUrl(buyer.getSourceUrl());
        r.setSourcePlatform(buyer.getSourcePlatform());
        r.setEmailStatus(buyer.getEmailStatus());
        r.setAlreadySent(emailLogRepository.existsByBuyerIdAndCampaignIdAndStatus(buyerId, campaignId, "SENT"));
        return r;
    }

    // Permanently removes a buyer from THIS campaign's recipient list only.
    // The buyer row and its relationships with other campaigns are untouched.
    public void removeRecipientFromCampaign(Long campaignId, Long buyerId) {
        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + campaignId));
        buyerRepository.findById(buyerId)
                .orElseThrow(() -> new ResourceNotFoundException("Buyer not found with id: " + buyerId));

        if (!exclusionRepository.existsByCampaignIdAndBuyerId(campaignId, buyerId)) {
            exclusionRepository.save(new CampaignRecipientExclusion(campaignId, buyerId));
        }
        logger.info("[Campaign {}] Buyer id={} permanently removed from this campaign's recipient list",
                campaignId, buyerId);
    }

    // Permanently removes the given buyers from THIS campaign's recipient list only.
    // The buyer rows, their email logs and their relationships with other campaigns
    // are untouched - each selected buyer is turned into a campaign exclusion.
    public Map<String, Object> removeRecipientsFromCampaign(Long campaignId, List<Long> buyerIds) {
        if (buyerIds == null || buyerIds.isEmpty()) {
            throw new IllegalArgumentException("No buyers selected for deletion");
        }

        Set<Long> uniqueBuyerIds = new LinkedHashSet<>();
        for (Long buyerId : buyerIds) {
            if (buyerId == null) {
                throw new IllegalArgumentException("buyerIds must not contain null values");
            }
            uniqueBuyerIds.add(buyerId);
        }

        campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + campaignId));

        Set<Long> existingBuyerIds = new HashSet<>();
        for (Long buyerId : uniqueBuyerIds) {
            if (buyerRepository.findById(buyerId).isPresent()) {
                existingBuyerIds.add(buyerId);
            }
        }
        for (Long buyerId : uniqueBuyerIds) {
            if (!existingBuyerIds.contains(buyerId)) {
                throw new ResourceNotFoundException("Buyer not found with id: " + buyerId);
            }
        }

        Set<Long> alreadyExcluded = new HashSet<>(
                exclusionRepository.findBuyerIdsByCampaignIdAndBuyerIdIn(campaignId, uniqueBuyerIds));
        List<CampaignRecipientExclusion> newExclusions = new ArrayList<>();
        for (Long buyerId : uniqueBuyerIds) {
            if (!alreadyExcluded.contains(buyerId)) {
                newExclusions.add(new CampaignRecipientExclusion(campaignId, buyerId));
            }
        }
        if (!newExclusions.isEmpty()) {
            exclusionRepository.saveAll(newExclusions);
        }

        logger.info("[Campaign {}] BULK DELETE: {}/{} selected buyer(s) permanently removed from this campaign's recipient list",
                campaignId, uniqueBuyerIds.size(), buyerIds.size());

        Map<String, Object> result = new HashMap<>();
        result.put("campaignId", campaignId);
        result.put("deleted", uniqueBuyerIds.size());
        result.put("buyerIds", new ArrayList<>(uniqueBuyerIds));
        return result;
    }

    public BulkCampaignResponse startCampaignSend(Long campaignId, SendCampaignRequest request) {
        logger.info("====================================================================");
        logger.info("[Campaign {}] FRONTEND SEND REQUESTED with {} recipient(s)", campaignId,
                request == null || request.getRecipients() == null ? 0 : request.getRecipients().size());

        if (request == null || request.getRecipients() == null || request.getRecipients().isEmpty()) {
            throw new IllegalArgumentException("No recipients provided. Add at least one recipient before sending.");
        }

        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + campaignId));

        Boolean alreadyActive = activeCampaigns.putIfAbsent(campaignId, Boolean.TRUE);
        if (alreadyActive != null) {
            throw new IllegalArgumentException("Campaign " + campaignId
                    + " is already being processed. Please wait for the current execution to finish.");
        }

        SendJob job = new SendJob(campaignId);
        job.total = request.getRecipients().size();
        sendJobs.put(campaignId, job);

        Thread worker = new Thread(() -> executeCampaignSend(campaign.getId(), request, job));
        worker.setName("campaign-send-" + campaignId);
        worker.setDaemon(true);
        worker.start();

        BulkCampaignResponse ack = new BulkCampaignResponse();
        ack.setCampaignId(campaign.getId());
        ack.setCampaignName(campaign.getCampaignName());
        ack.getDetails().add("Send started in background for " + request.getRecipients().size() + " recipient(s).");
        return ack;
    }

    public Map<String, Object> getSendJobStatus(Long campaignId) {
        SendJob job = sendJobs.get(campaignId);
        Map<String, Object> status = new HashMap<>();
        if (job == null) {
            status.put("running", false);
            status.put("total", 0);
            status.put("processed", 0);
            status.put("sent", 0);
            status.put("failed", 0);
            status.put("skipped", 0);
            return status;
        }
        status.put("running", job.running);
        status.put("total", job.total);
        status.put("processed", job.processed);
        status.put("sent", job.sent);
        status.put("failed", job.failed);
        status.put("skipped", job.skipped);
        if (job.error != null) status.put("error", job.error);
        if (!job.running && job.result != null) status.put("result", job.result);
        return status;
    }

    public Map<String, Object> getTrackingStats() {
        return getTrackingStats(null, null);
    }

    /**
     * Email tracking stats, optionally bounded by a date range on the actual
     * email send timestamp (email_logs.sent_at). When no dates are given the
     * response falls back to all-time counts, preserving the original keys
     * ("sent", "pending", "responses") for existing consumers.
     */
    public Map<String, Object> getTrackingStats(LocalDate startDate, LocalDate endDate) {
        if ((startDate == null) != (endDate == null)) {
            throw new IllegalArgumentException("Both startDate and endDate are required for date filtering.");
        }

        long successful;
        long failed;
        if (startDate != null) {
            if (startDate.isAfter(endDate)) {
                throw new IllegalArgumentException("Start date must be on or before end date.");
            }
            LocalDateTime rangeStart = startDate.atStartOfDay();
            LocalDateTime rangeEnd = endDate.plusDays(1).atStartOfDay();
            successful = emailLogRepository.countSentAtBetweenAndStatus(rangeStart, rangeEnd, "SENT");
            failed = emailLogRepository.countSentAtBetweenAndStatus(rangeStart, rangeEnd, "FAILED");
        } else {
            successful = emailLogRepository.countByStatus("SENT");
            failed = emailLogRepository.countByStatus("FAILED");
        }

        long pending = buyerRepository.countPendingContactable();

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", successful + failed);
        stats.put("successful", successful);
        stats.put("failed", failed);
        stats.put("pending", pending);
        if (startDate != null) {
            stats.put("startDate", startDate.toString());
            stats.put("endDate", endDate.toString());
        }
        stats.put("sent", successful);
        stats.put("responses", responseRepository.countAllResponses());
        return stats;
    }

    /**
     * Email summary for the Email Tracking "Email Summary" view: the total
     * number of logged emails plus their status breakdown (SENT, FAILED, ...).
     * With no dates the summary covers all time. The same email_logs.sent_at
     * timestamp and inclusive day boundaries are used as getTrackingStats, so
     * a summary and the tracking stats always agree for an identical range.
     */
    public Map<String, Object> getEmailSummary(LocalDate startDate, LocalDate endDate) {
        if ((startDate == null) != (endDate == null)) {
            throw new IllegalArgumentException("Both startDate and endDate are required for date filtering.");
        }
        if (startDate != null && startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date must be on or before end date.");
        }

        List<EmailLogRepository.StatusCountRow> rows;
        if (startDate != null) {
            LocalDateTime rangeStart = startDate.atStartOfDay();
            LocalDateTime rangeEnd = endDate.plusDays(1).atStartOfDay();
            rows = emailLogRepository.countGroupedByStatusBetween(rangeStart, rangeEnd);
        } else {
            rows = emailLogRepository.countGroupedByStatus();
        }

        Map<String, Long> countsByStatus = new LinkedHashMap<>();
        long total = 0;
        for (EmailLogRepository.StatusCountRow row : rows) {
            String status = row.getStatus() == null || row.getStatus().isBlank()
                    ? "UNKNOWN"
                    : row.getStatus().trim().toUpperCase(Locale.ROOT);
            countsByStatus.merge(status, row.getCnt(), Long::sum);
            total += row.getCnt();
        }

        List<Map<String, Object>> statusCounts = new ArrayList<>();
        for (Map.Entry<String, Long> entry : countsByStatus.entrySet()) {
            long count = entry.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("status", entry.getKey());
            item.put("count", count);
            item.put("percentage", total > 0 ? Math.round((count * 100.0) / total) : 0L);
            statusCounts.add(item);
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("startDate", startDate != null ? startDate.toString() : null);
        summary.put("endDate", endDate != null ? endDate.toString() : null);
        summary.put("total", total);
        summary.put("successful", countsByStatus.getOrDefault("SENT", 0L));
        summary.put("failed", countsByStatus.getOrDefault("FAILED", 0L));
        summary.put("statusCounts", statusCounts);
        return summary;
    }

    public static class SendJob {
        public final Long campaignId;
        public volatile boolean running = true;
        public volatile int total = 0;
        public volatile int processed = 0;
        public volatile int sent = 0;
        public volatile int failed = 0;
        public volatile int skipped = 0;
        public volatile String error;
        public volatile BulkCampaignResponse result;

        public SendJob(Long campaignId) { this.campaignId = campaignId; }
    }

    private void executeCampaignSend(Long campaignId, SendCampaignRequest request, SendJob job) {
        BulkCampaignResponse summary = new BulkCampaignResponse();

        try {
            Campaign campaign = campaignRepository.findById(campaignId)
                    .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + campaignId));
            summary.setCampaignId(campaign.getId());
            summary.setCampaignName(campaign.getCampaignName());

            String fromAddress = (mailFrom != null && !mailFrom.isBlank()) ? mailFrom : campaign.getSenderEmail();
            if (fromAddress == null || fromAddress.isBlank()) {
                throw new RuntimeException("Configuration error: mail.from property is not set and campaign has no sender email.");
            }

            int sendingLimit = (campaign.getSendingLimit() != null && campaign.getSendingLimit() > 0)
                    ? campaign.getSendingLimit() : Integer.MAX_VALUE;
            int delaySeconds = (campaign.getDelayBetweenEmails() != null && campaign.getDelayBetweenEmails() > 0)
                    ? campaign.getDelayBetweenEmails() : 5;

            logger.info("[Campaign {}] Sending limit: {}, delay between emails: {}s",
                    campaignId,
                    sendingLimit == Integer.MAX_VALUE ? "UNLIMITED" : sendingLimit,
                    delaySeconds);

            List<SendCampaignRequest.Recipient> queue = new ArrayList<>();
            Set<String> seenEmails = new HashSet<>();

            for (SendCampaignRequest.Recipient item : request.getRecipients()) {
                if (item == null || item.getBuyerId() == null) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    summary.getResults().add(RecipientResult.of(null, null,
                            item != null ? item.getEmail() : null, "SKIPPED", "Missing buyerId"));
                    continue;
                }

                Buyer buyer = buyerRepository.findById(item.getBuyerId()).orElse(null);
                if (buyer == null) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    summary.getResults().add(RecipientResult.of(item.getBuyerId(), null, item.getEmail(), "SKIPPED", "Buyer not found"));
                    continue;
                }

                String email = item.getEmail() != null ? item.getEmail().trim().toLowerCase() : "";
                if (email.isEmpty()) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    summary.getResults().add(RecipientResult.of(buyer, email, "SKIPPED", "No email address provided"));
                    continue;
                }
                if (!EMAIL_PATTERN.matcher(email).matches()) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    summary.getResults().add(RecipientResult.of(buyer, email, "SKIPPED", "Invalid email format: " + email));
                    continue;
                }
                if (!seenEmails.add(email)) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    summary.getResults().add(RecipientResult.of(buyer.getId(), buyer.getBuyerName(), email, "SKIPPED", "Duplicate email in recipient list"));
                    continue;
                }
                if (emailLogRepository.existsByBuyerIdAndCampaignIdAndStatus(buyer.getId(), campaignId, "SENT")) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    summary.getResults().add(RecipientResult.of(buyer.getId(), buyer.getBuyerName(), email, "SKIPPED",
                            "Already sent this campaign - duplicate prevention"));
                    continue;
                }
                if (queue.size() >= sendingLimit) {
                    summary.setTotalSkipped(summary.getTotalSkipped() + 1);
                    summary.getResults().add(RecipientResult.of(buyer.getId(), buyer.getBuyerName(), email, "SKIPPED",
                            "Campaign sending limit reached (" + sendingLimit + ")"));
                    continue;
                }

                item.setEmail(email);
                queue.add(item);
            }

            summary.setTotalEligible(queue.size());
            job.total = queue.size();
            logger.info("[Campaign {}] Validated recipients: eligible={} skipped={}", campaignId, queue.size(), summary.getTotalSkipped());

            for (int i = 0; i < queue.size(); i++) {
                SendCampaignRequest.Recipient item = queue.get(i);
                Buyer buyer = buyerRepository.findById(item.getBuyerId()).orElse(null);
                if (buyer == null) {
                    continue;
                }

                summary.setTotalAttempted(summary.getTotalAttempted() + 1);

                String productName = buyer.getProductName() != null ? buyer.getProductName()
                        : (campaign.getProductName() != null ? campaign.getProductName() : "");
                String subject = personalizeTextFull(campaign.getEmailSubject(), buyer.getBuyerName(), buyer.getCompanyName(), productName, buyer.getCountry());
                String body = personalizeTextFull(campaign.getEmailBody(), buyer.getBuyerName(), buyer.getCompanyName(), productName, buyer.getCountry());

                EmailLog emailLog = new EmailLog();
                emailLog.setBuyerId(buyer.getId());
                emailLog.setCampaignId(campaign.getId());
                emailLog.setEmail(item.getEmail());
                emailLog.setSubject(subject);

                try {
                    if (smtpUsername != null && !smtpUsername.isBlank()
                            && fromAddress != null && !fromAddress.equalsIgnoreCase(smtpUsername)) {
                        logger.warn("[Campaign {}] Frontend send: sender '{}' does not match authenticated Gmail '{}'. Overriding.",
                                campaignId, fromAddress, smtpUsername);
                        fromAddress = smtpUsername;
                    }

                    MimeMessage sentMessage = sendMimeWithAttachment(fromAddress, item.getEmail(), subject, body, campaign);

                    emailLog.setStatus("SENT");
                    emailLog.setSentAt(LocalDateTime.now());
                    emailLog.setMessageId(readMessageId(sentMessage));
                    emailLogRepository.save(emailLog);

                    buyer.setEmailStatus("CONTACTED");
                    buyer.setContactedAt(LocalDateTime.now());
                    buyerRepository.save(buyer);

                    summary.setTotalSent(summary.getTotalSent() + 1);
                    summary.getResults().add(RecipientResult.of(buyer.getId(), buyer.getBuyerName(), item.getEmail(), "SENT", "Delivered"));
                    summary.getDetails().add("SENT buyerId=" + buyer.getId() + " " + item.getEmail());
                    logger.info("[Campaign {}] SENT {}/{} buyerId={} email={}", campaignId, i + 1, queue.size(), buyer.getId(), item.getEmail());

                } catch (Exception e) {
                    String rootCause = getRootCauseMessage(e);
                    logger.error("[Campaign {}] FAILED buyerId={} email={} SMTP root cause: {}",
                            campaignId, buyer.getId(), item.getEmail(), rootCause, e);
                    emailLog.setStatus("FAILED");
                    emailLog.setSentAt(LocalDateTime.now());
                    emailLog.setErrorMessage("SMTP error: " + rootCause);
                    emailLogRepository.save(emailLog);

                    summary.setTotalFailed(summary.getTotalFailed() + 1);
                    summary.getResults().add(RecipientResult.of(buyer.getId(), buyer.getBuyerName(), item.getEmail(), "FAILED", "SMTP error: " + rootCause));
                    summary.getDetails().add("FAILED buyerId=" + buyer.getId() + " " + item.getEmail() + " error=" + rootCause);
                }

                job.processed = i + 1;
                job.sent = summary.getTotalSent();
                job.failed = summary.getTotalFailed();
                job.skipped = summary.getTotalSkipped();

                if (i < queue.size() - 1) {
                    try {
                        Thread.sleep(delaySeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("[Campaign {}] INTERRUPTED after buyerId={}", campaignId, buyer.getId());
                        break;
                    }
                }
            }

            logger.info("====================================================================");
            logger.info("[Campaign {}] FRONTEND SEND COMPLETE: eligible={} attempted={} sent={} failed={} skipped={}",
                    campaignId, summary.getTotalEligible(), summary.getTotalAttempted(),
                    summary.getTotalSent(), summary.getTotalFailed(), summary.getTotalSkipped());
            logger.info("====================================================================");

            summary.setCompletedAt(LocalDateTime.now());
            job.result = summary;

        } catch (Exception e) {
            String rootCause = getRootCauseMessage(e);
            logger.error("[Campaign {}] SEND ABORTED: {}", campaignId, rootCause, e);
            summary.getDetails().add("ABORTED: " + rootCause);
            job.error = rootCause;
            job.result = summary;
        } finally {
            job.running = false;
            activeCampaigns.remove(campaignId);
            logger.info("[Campaign {}] Campaign lock released", campaignId);
        }
    }

    private MimeMessage sendMimeWithAttachment(String fromAddress, String toEmail, String subject, String body, Campaign campaign)
            throws MessagingException {
        if (smtpUsername != null && !smtpUsername.isBlank()
                && fromAddress != null && !fromAddress.equalsIgnoreCase(smtpUsername)) {
            logger.warn("Sender email '{}' does not match authenticated Gmail account '{}'. "
                    + "Gmail may reject this email. Overriding sender to '{}'.",
                    fromAddress, smtpUsername, smtpUsername);
            fromAddress = smtpUsername;
        }

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);
        helper.setFrom(fromAddress);
        helper.setTo(toEmail);
        helper.setSubject(subject);
        helper.setText(ensureHtmlBody(body), true);

        if (campaign.getAttachmentPath() != null && !campaign.getAttachmentPath().isEmpty()) {
            Path attachmentPath = Paths.get(campaign.getAttachmentPath());
            if (Files.exists(attachmentPath)) {
                FileSystemResource resource = new FileSystemResource(attachmentPath.toFile());
                String attachmentName = campaign.getAttachmentName() != null
                        ? campaign.getAttachmentName()
                        : attachmentPath.getFileName().toString();
                helper.addAttachment(attachmentName, resource);
            } else {
                logger.warn("Attachment file not found at: {}", campaign.getAttachmentPath());
            }
        }

        mailSender.send(message);
        return message;
    }

    private String readMessageId(MimeMessage message) {
        try {
            return message.getMessageID();
        } catch (MessagingException e) {
            logger.warn("Could not read Message-ID after send: {}", e.getMessage());
            return null;
        }
    }
}
