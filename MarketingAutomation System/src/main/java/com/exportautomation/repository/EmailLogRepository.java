package com.exportautomation.repository;

import com.exportautomation.entity.EmailLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EmailLogRepository extends JpaRepository<EmailLog, Long> {

    boolean existsByBuyerIdAndCampaignIdAndStatus(Long buyerId, Long campaignId, String status);

    boolean existsByBuyerIdAndStatus(Long buyerId, String status);

    @Query("SELECT COUNT(e) FROM EmailLog e WHERE e.campaignId = :campaignId AND e.status = 'SENT'")
    long countSentByCampaignId(@Param("campaignId") Long campaignId);

    @Query("SELECT COUNT(e) FROM EmailLog e WHERE e.campaignId = :campaignId AND e.buyerId = :buyerId AND e.email = :email AND e.status = 'SENT'")
    long countSentByCampaignBuyerEmail(@Param("campaignId") Long campaignId,
                                       @Param("buyerId") Long buyerId,
                                       @Param("email") String email);

    List<EmailLog> findByCampaignIdAndBuyerIdAndStatus(Long campaignId, Long buyerId, String status);

    List<EmailLog> findTop20ByBuyerIdOrderBySentAtDesc(Long buyerId);

    @Query("SELECT e FROM EmailLog e WHERE e.messageId IS NOT NULL AND e.status = 'SENT'")
    List<EmailLog> findSentWithMessageId();

    long countByStatus(String status);

    @Query("SELECT COUNT(e) FROM EmailLog e WHERE e.sentAt >= :start AND e.sentAt < :end AND e.status = :status")
    long countSentAtBetweenAndStatus(@Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end,
                                     @Param("status") String status);

    @Query("SELECT e.status AS status, COUNT(e) AS cnt FROM EmailLog e GROUP BY e.status ORDER BY cnt DESC")
    List<StatusCountRow> countGroupedByStatus();

    @Query("SELECT e.status AS status, COUNT(e) AS cnt FROM EmailLog e "
           + "WHERE e.sentAt >= :start AND e.sentAt < :end "
           + "GROUP BY e.status ORDER BY cnt DESC")
    List<StatusCountRow> countGroupedByStatusBetween(@Param("start") LocalDateTime start,
                                                     @Param("end") LocalDateTime end);

    @Modifying
    @Query("DELETE FROM EmailLog e WHERE e.campaignId = :campaignId")
    void deleteByCampaignId(@Param("campaignId") Long campaignId);

    /** Row of the grouped status-count queries above (status -> number of email logs). */
    interface StatusCountRow {
        String getStatus();
        long getCnt();
    }
}
