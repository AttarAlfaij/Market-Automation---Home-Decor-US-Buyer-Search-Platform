package com.exportautomation.repository;

import com.exportautomation.entity.CampaignResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CampaignResponseRepository extends JpaRepository<CampaignResponse, Long> {

    long countByBuyerIdAndCampaignId(Long buyerId, Long campaignId);

    boolean existsByBuyerIdAndCampaignIdAndResponseDate(Long buyerId, Long campaignId, LocalDateTime responseDate);

    boolean existsByBuyerIdAndCampaignIdAndEmailAndResponseDateBetween(
            Long buyerId, Long campaignId, String email, LocalDateTime start, LocalDateTime end);

    List<CampaignResponse> findByBuyerIdOrderByResponseDateDesc(Long buyerId);

    List<CampaignResponse> findByCampaignIdOrderByResponseDateDesc(Long campaignId);

    List<CampaignResponse> findAllByOrderByResponseDateDesc();

    boolean existsByMessageId(String messageId);

    @Query("SELECT COUNT(cr) FROM CampaignResponse cr")
    long countAllResponses();

    @Query("SELECT COUNT(cr) FROM CampaignResponse cr WHERE cr.responseType IS NULL OR LOWER(cr.responseType) <> 'auto_reply'")
    long countGenuineResponses();

    @Query("SELECT cr.email FROM CampaignResponse cr WHERE cr.email IS NOT NULL")
    List<String> findAllResponseEmails();
}
