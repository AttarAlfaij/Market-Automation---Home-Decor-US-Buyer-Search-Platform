package com.exportautomation.service;

import com.exportautomation.dto.LeadRequest;
import com.exportautomation.entity.Buyer;
import com.exportautomation.entity.CampaignResponse;
import com.exportautomation.entity.EmailLog;
import com.exportautomation.entity.Lead;
import com.exportautomation.repository.BuyerRepository;
import com.exportautomation.repository.CampaignResponseRepository;
import com.exportautomation.repository.EmailLogRepository;
import com.exportautomation.repository.LeadRepository;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class LeadService {

    private static final Logger logger = LoggerFactory.getLogger(LeadService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private CampaignResponseRepository responseRepository;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private BuyerRepository buyerRepository;

    @Value("${mail.imap.host:}")
    private String imapHost;

    @Value("${mail.imap.port:993}")
    private int imapPort;

    @Value("${mail.imap.username:}")
    private String imapUsername;

    @Value("${mail.imap.password:}")
    private String imapPassword;

    @Value("${mail.imap.enabled:false}")
    private boolean imapEnabled;

    // ── Lead CRUD ──────────────────────────────────────────────────────

    public List<Lead> getAllLeads() {
        return leadRepository.findAllByOrderByCreatedAtDesc();
    }

    public Lead addLead(LeadRequest request) {
        Lead lead = new Lead();
        lead.setBuyerName(request.getBuyerName());
        lead.setCompanyName(request.getCompanyName());
        lead.setEmail(request.getEmail());
        lead.setNotes(request.getNotes());
        lead.setLeadStatus(request.getLeadStatus() != null ? request.getLeadStatus() : "NEW");
        return leadRepository.save(lead);
    }

    public Lead updateLead(Long id, LeadRequest request) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Lead not found with id: " + id));
        if (request.getBuyerName() != null) lead.setBuyerName(request.getBuyerName());
        if (request.getCompanyName() != null) lead.setCompanyName(request.getCompanyName());
        if (request.getEmail() != null) lead.setEmail(request.getEmail());
        if (request.getNotes() != null) lead.setNotes(request.getNotes());
        if (request.getLeadStatus() != null) lead.setLeadStatus(request.getLeadStatus());
        return leadRepository.save(lead);
    }

    public void deleteLead(Long id) {
        leadRepository.deleteById(id);
    }

    // ── Response CRUD ──────────────────────────────────────────────────

    public List<CampaignResponse> getAllResponses() {
        return responseRepository.findAllByOrderByResponseDateDesc();
    }

    public CampaignResponse addResponse(CampaignResponse response) {
        if (response.getMessageId() != null && !response.getMessageId().isBlank()
                && responseRepository.existsByMessageId(response.getMessageId())) {
            logger.info("Duplicate response skipped by Message-ID: {}", response.getMessageId());
            return null;
        }
        if (response.getBuyerId() != null && response.getCampaignId() != null
                && response.getResponseDate() != null) {
            LocalDateTime start = response.getResponseDate().minusMinutes(1);
            LocalDateTime end = response.getResponseDate().plusMinutes(1);
            boolean dup = responseRepository
                    .existsByBuyerIdAndCampaignIdAndEmailAndResponseDateBetween(
                            response.getBuyerId(), response.getCampaignId(),
                            response.getEmail(), start, end);
            if (dup) {
                logger.info("Duplicate response skipped: buyerId={} campaignId={} date={}",
                        response.getBuyerId(), response.getCampaignId(), response.getResponseDate());
                return null;
            }
        }
        return responseRepository.save(response);
    }

    // ── Stats ──────────────────────────────────────────────────────────

    public Map<String, Object> getLeadsAndResponsesStats() {
        Map<String, Object> stats = new HashMap<>();
        long responseCount = responseRepository.countAllResponses();
        long leadCount = leadRepository.countAllLeads();
        long totalEmailsSent = emailLogRepository.countByStatus("SENT");
        // Exclude AUTO_REPLY (out-of-office, bounces) from the response rate so it
        // reflects genuine buyer replies only.
        long genuineResponses = responseRepository.countGenuineResponses();
        double responseRate = totalEmailsSent > 0 ? Math.round((genuineResponses * 100.0) / totalEmailsSent) : 0;
        stats.put("responses", responseCount);
        stats.put("leads", leadCount);
        stats.put("responseRate", responseRate);
        return stats;
    }

    // ── IMAP Reply Detection ───────────────────────────────────────────

    @Scheduled(fixedDelayString = "${mail.imap.poll.interval:300000}", initialDelay = 60000)
    public void autoCheckForReplies() {
        if (!imapEnabled || imapHost == null || imapHost.isBlank()) {
            return;
        }
        logger.info("Starting automatic reply detection via IMAP...");
        try {
            int detected = checkForReplies();
            if (detected > 0) {
                logger.info("Auto reply detection found {} new response(s)", detected);
            }
        } catch (Exception e) {
            logger.error("Error during auto reply detection: {}", e.getMessage(), e);
        }
    }

    public int checkForReplies() {
        if (!imapEnabled || imapHost == null || imapHost.isBlank()) {
            logger.info("IMAP reply detection is not configured. Set mail.imap.enabled=true and mail.imap.host to enable.");
            return 0;
        }
        if (imapUsername == null || imapUsername.isBlank()
                || imapPassword == null || imapPassword.isBlank()) {
            logger.warn("IMAP credentials are not configured. Set MAIL_IMAP_USERNAME and MAIL_IMAP_PASSWORD environment variables.");
            return 0;
        }

        int detected = 0;
        Store store = null;
        Folder inbox = null;

        try {
            Properties props = new Properties();
            props.put("mail.store.protocol", "imaps");
            props.put("mail.imaps.host", imapHost);
            props.put("mail.imaps.port", String.valueOf(imapPort));
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.connectiontimeout", "20000");
            props.put("mail.imaps.timeout", "30000");

            Session session = Session.getInstance(props);
            store = session.getStore("imaps");
            store.connect(imapHost, imapPort, imapUsername, imapPassword);

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            // All campaign emails we have sent (buyer-linked, SENT status)
            List<EmailLog> sentEmails = emailLogRepository.findAll().stream()
                    .filter(e -> "SENT".equals(e.getStatus()) && e.getBuyerId() != null)
                    .toList();

            // Index sent emails by buyer email (lowercased) for sender-email matching
            Map<String, List<EmailLog>> sentByBuyerEmail = new HashMap<>();
            for (EmailLog log : sentEmails) {
                String buyerEmail = log.getEmail();
                if (buyerEmail != null && !buyerEmail.isBlank()) {
                    sentByBuyerEmail
                            .computeIfAbsent(buyerEmail.toLowerCase().trim(), k -> new ArrayList<>())
                            .add(log);
                }
            }

            // Index sent emails by their outbound Message-ID for reference-chain matching
            Map<String, EmailLog> sentByMessageId = new HashMap<>();
            for (EmailLog log : sentEmails) {
                if (log.getMessageId() != null && !log.getMessageId().isBlank()) {
                    sentByMessageId.put(normalizeMessageId(log.getMessageId()), log);
                }
            }

            Set<String> seenReplyMessageIds = new HashSet<>();

            Message[] messages = inbox.getMessages();
            logger.info("Scanning {} message(s) in INBOX for campaign replies", messages.length);

            for (Message message : messages) {
                try {
                    // Unique id of this incoming reply - used for de-duplication
                    String replyMessageId = firstHeader(message, "Message-ID");
                    if (replyMessageId != null) {
                        String normReplyId = normalizeMessageId(replyMessageId);
                        if (!seenReplyMessageIds.add(normReplyId)) continue;
                        // Already recorded previously
                        if (responseRepository.existsByMessageId(normReplyId)) continue;
                    }

                    Address[] fromAddresses = message.getFrom();
                    if (fromAddresses == null || fromAddresses.length == 0) continue;
                    String fromEmail = extractEmail(fromAddresses[0].toString()).toLowerCase().trim();
                    if (!EMAIL_PATTERN.matcher(fromEmail).matches()) continue;

                    Date sentDate = message.getSentDate();
                    LocalDateTime responseDate = sentDate != null
                            ? LocalDateTime.ofInstant(sentDate.toInstant(), ZoneId.systemDefault())
                            : LocalDateTime.now();

                    String subject = message.getSubject() != null ? message.getSubject() : "";
                    String inReplyTo = firstHeader(message, "In-Reply-To");
                    String references = firstHeader(message, "References");

                    // ── Match against a specific sent email ─────────────────────
                    // 1. Prefer Message-ID reference chain (In-Reply-To / References)
                    EmailLog matchedLog = resolveByReferences(inReplyTo, references, sentByMessageId);

                    // 2. Fall back to folder/campaign by sender email + subject
                    List<EmailLog> senderLogs = sentByBuyerEmail.get(fromEmail);
                    if (matchedLog == null && senderLogs != null && !senderLogs.isEmpty()) {
                        matchedLog = resolveBySubject(senderLogs, subject);
                    }

                    if (matchedLog == null) continue;

                    Buyer buyer = buyerRepository.findById(matchedLog.getBuyerId()).orElse(null);

                    CampaignResponse response = new CampaignResponse();
                    response.setBuyerId(matchedLog.getBuyerId());
                    response.setCampaignId(matchedLog.getCampaignId());
                    response.setEmail(fromEmail);
                    response.setBuyerName(buyer != null ? buyer.getBuyerName() : null);
                    response.setCompanyName(buyer != null ? buyer.getCompanyName() : null);
                    response.setMessageId(normalizeMessageId(replyMessageId));
                    response.setSubject(subject);
                    response.setResponseType(classifyResponseType(subject, message));
                    response.setBodyPreview(extractTextPreview(message));
                    response.setResponseDate(responseDate);

                    CampaignResponse saved = addResponse(response);
                    if (saved != null) {
                        detected++;
                        logger.info("Recorded campaign response: buyerId={} campaignId={} from={} type={}",
                                matchedLog.getBuyerId(), matchedLog.getCampaignId(), fromEmail, saved.getResponseType());
                    }
                } catch (Exception e) {
                    logger.warn("Error processing message for reply detection: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            logger.error("IMAP reply detection failed: {}", e.getMessage(), e);
        } finally {
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) {}
            try { if (store != null) store.close(); } catch (Exception ignored) {}
        }

        return detected;
    }

    private EmailLog resolveByReferences(String inReplyTo, String references,
                                         Map<String, EmailLog> sentByMessageId) {
        if (sentByMessageId.isEmpty()) return null;
        Set<String> candidateIds = new HashSet<>();
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            candidateIds.addAll(extractMessageIds(inReplyTo));
        }
        if (references != null && !references.isBlank()) {
            candidateIds.addAll(extractMessageIds(references));
        }
        if (candidateIds.isEmpty()) return null;
        for (String id : candidateIds) {
            EmailLog log = sentByMessageId.get(normalizeMessageId(id));
            if (log != null) return log;
        }
        return null;
    }

    private EmailLog resolveBySubject(List<EmailLog> senderLogs, String subject) {
        if (senderLogs == null || senderLogs.isEmpty()) return null;
        String cleanSubject = subject.toLowerCase().replaceAll("^re:\\s*", "").trim();
        if (cleanSubject.isEmpty()) return senderLogs.get(0);

        // Prefer exact subject match, then the most recent email for this buyer
        for (EmailLog log : senderLogs) {
            if (log.getSubject() != null
                    && log.getSubject().toLowerCase().trim().equals(cleanSubject)) {
                return log;
            }
        }
        // Sort by sent date desc and pick the most recent
        return senderLogs.stream()
                .min((a, b) -> {
                    java.time.LocalDateTime da = a.getSentAt() != null ? a.getSentAt() : java.time.LocalDateTime.MIN;
                    java.time.LocalDateTime db = b.getSentAt() != null ? b.getSentAt() : java.time.LocalDateTime.MIN;
                    return db.compareTo(da);
                })
                .orElse(senderLogs.get(0));
    }

    private String classifyResponseType(String subject, Message message) {
        String subj = subject == null ? "" : subject.toLowerCase();
        String sender = "";
        try {
            Address[] from = message.getFrom();
            if (from != null && from.length > 0) sender = from[0].toString().toLowerCase();
        } catch (Exception ignored) {}

        if (subj.contains("out of office") || subj.contains("automatic reply")
                || subj.contains("auto-reply") || subj.contains("auto reply")
                || subj.contains("away from") || subj.contains("absense")
                || subj.contains("vacation") || sender.contains("noreply")
                || sender.contains("no-reply") || sender.contains("notifications")
                || sender.contains("mailer-daemon") || sender.contains("postmaster")) {
            return "AUTO_REPLY";
        }
        return "REPLY";
    }

    private String firstHeader(Message message, String headerName) {
        try {
            String[] values = message.getHeader(headerName);
            return (values != null && values.length > 0) ? values[0] : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractEmail(String address) {
        if (address == null) return "";
        int lt = address.indexOf('<');
        int gt = address.indexOf('>');
        if (lt >= 0 && gt > lt) return address.substring(lt + 1, gt);
        return address.trim();
    }

    private String normalizeMessageId(String messageId) {
        return messageId == null ? "" : messageId.replaceAll("[<>\\s]", "").trim();
    }

    private Set<String> extractMessageIds(String headerValue) {
        Set<String> ids = new HashSet<>();
        if (headerValue == null) return ids;
        // Split on whitespace, dropping any angle brackets
        for (String token : headerValue.split("\\s+")) {
            String cleaned = normalizeMessageId(token);
            if (!cleaned.isEmpty()) ids.add(cleaned);
        }
        return ids;
    }

    private String extractTextPreview(Message message) {
        try {
            if (message.getContent() instanceof String text) {
                return text.length() > 500 ? text.substring(0, 500) : text;
            }
            if (message.getContent() instanceof MimeMultipart multipart) {
                return getTextFromMultipart(multipart);
            }
        } catch (IOException | MessagingException e) {
            logger.debug("Could not extract text preview: {}", e.getMessage());
        }
        return "";
    }

    private String getTextFromMultipart(MimeMultipart multipart) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (!bodyPart.getContentType().toLowerCase().contains("text/plain")) continue;
            String text = bodyPart.getContent().toString();
            return text.length() > 500 ? text.substring(0, 500) : text;
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart bodyPart = multipart.getBodyPart(i);
            if (bodyPart.getContentType().toLowerCase().contains("text/html")) {
                String html = bodyPart.getContent().toString();
                String text = html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
                return text.length() > 500 ? text.substring(0, 500) : text;
            }
            if (bodyPart.getContent() instanceof MimeMultipart nested) {
                String result = getTextFromMultipart(nested);
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    public boolean isImapEnabled() {
        return imapEnabled && imapHost != null && !imapHost.isBlank();
    }
}
