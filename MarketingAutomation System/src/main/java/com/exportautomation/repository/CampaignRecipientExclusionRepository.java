package com.exportautomation.repository;

import com.exportautomation.entity.CampaignRecipientExclusion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignRecipientExclusionRepository extends JpaRepository<CampaignRecipientExclusion, Long> {

    boolean existsByCampaignIdAndBuyerId(Long campaignId, Long buyerId);

    @Query("SELECT e.buyerId FROM CampaignRecipientExclusion e WHERE e.campaignId = :campaignId")
    List<Long> findBuyerIdsByCampaignId(@Param("campaignId") Long campaignId);

    @Modifying
    @Query("DELETE FROM CampaignRecipientExclusion e WHERE e.campaignId = :campaignId")
    void deleteByCampaignId(@Param("campaignId") Long campaignId);

    @Modifying
    @Query("DELETE FROM CampaignRecipientExclusion e WHERE e.buyerId = :buyerId")
    void deleteByBuyerId(@Param("buyerId") Long buyerId);
}