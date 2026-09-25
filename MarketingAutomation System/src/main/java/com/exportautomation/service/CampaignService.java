package com.exportautomation.service;

import com.exportautomation.entity.Campaign;
import com.exportautomation.exception.ResourceNotFoundException;
import com.exportautomation.repository.CampaignRecipientExclusionRepository;
import com.exportautomation.repository.CampaignRepository;
import com.exportautomation.repository.EmailLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class CampaignService {

    private static final Logger logger = LoggerFactory.getLogger(CampaignService.class);

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private EmailLogRepository emailLogRepository;

    @Autowired
    private CampaignRecipientExclusionRepository exclusionRepository;

    @Value("${campaign.upload.dir:uploads/campaign-attachments}")
    private String uploadDir;

    public List<Campaign> getAllCampaigns() {
        return campaignRepository.findAll();
    }

    public Campaign getCampaignById(Long id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign not found with id: " + id));
    }

    public Campaign createCampaign(Campaign campaign, MultipartFile file) throws IOException {
        if (file != null && !file.isEmpty()) {
            String savedPath = saveFile(file);
            campaign.setAttachmentName(file.getOriginalFilename());
            campaign.setAttachmentPath(savedPath);
        }
        Campaign saved = campaignRepository.save(campaign);
        logger.info("Campaign created with id: {}", saved.getId());
        return saved;
    }

    public Campaign updateCampaign(Long id, Campaign updatedCampaign, MultipartFile file) throws IOException {
        Campaign existing = getCampaignById(id);

        existing.setCampaignName(updatedCampaign.getCampaignName());
        existing.setEmailSubject(updatedCampaign.getEmailSubject());
        existing.setEmailBody(updatedCampaign.getEmailBody());
        existing.setTargetAudience(updatedCampaign.getTargetAudience());
        existing.setSendingLimit(updatedCampaign.getSendingLimit());
        existing.setDelayBetweenEmails(updatedCampaign.getDelayBetweenEmails());
        existing.setProductName(updatedCampaign.getProductName());
        existing.setProductCategory(updatedCampaign.getProductCategory());
        existing.setExportAvailability(updatedCampaign.getExportAvailability());
        existing.setCustomizationOptions(updatedCampaign.getCustomizationOptions());
        existing.setMinimumOrderQuantity(updatedCampaign.getMinimumOrderQuantity());
        existing.setShippingAvailability(updatedCampaign.getShippingAvailability());
        existing.setSenderName(updatedCampaign.getSenderName());
        existing.setSenderCompany(updatedCampaign.getSenderCompany());
        existing.setSenderEmail(updatedCampaign.getSenderEmail());
        existing.setSenderPhone(updatedCampaign.getSenderPhone());

        if (file != null && !file.isEmpty()) {
            deleteExistingFile(existing.getAttachmentPath());
            String savedPath = saveFile(file);
            existing.setAttachmentName(file.getOriginalFilename());
            existing.setAttachmentPath(savedPath);
        }

        Campaign saved = campaignRepository.save(existing);
        logger.info("Campaign updated with id: {}", saved.getId());
        return saved;
    }

    @Transactional
    public void deleteCampaign(Long id) {
        Campaign existing = getCampaignById(id);
        emailLogRepository.deleteByCampaignId(id);
        exclusionRepository.deleteByCampaignId(id);
        deleteExistingFile(existing.getAttachmentPath());
        campaignRepository.deleteById(id);
        logger.info("Campaign deleted with id: {}", id);
    }

    private String saveFile(MultipartFile file) throws IOException {
        Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(uploadPath);

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String uniqueFilename = UUID.randomUUID().toString() + extension;

        Path targetLocation = uploadPath.resolve(uniqueFilename);
        Files.copy(file.getInputStream(), targetLocation);

        return targetLocation.toString();
    }

    private void deleteExistingFile(String filePath) {
        if (filePath != null) {
            try {
                Path path = Paths.get(filePath);
                Files.deleteIfExists(path);
            } catch (IOException e) {
                logger.warn("Could not delete file: {}", filePath, e);
            }
        }
    }
}
