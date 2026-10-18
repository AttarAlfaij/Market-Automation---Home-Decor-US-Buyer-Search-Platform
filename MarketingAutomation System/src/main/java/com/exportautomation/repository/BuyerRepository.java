package com.exportautomation.repository;

import com.exportautomation.entity.Buyer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface BuyerRepository extends JpaRepository<Buyer, Long> {

    Optional<Buyer> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, Long id);

    @Query("SELECT b FROM Buyer b WHERE " +
            "LOWER(b.buyerName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.companyName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.country) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.state) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.city) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.location) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.productName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.sourcePlatform) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(b.businessType) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Buyer> searchByKeyword(@Param("keyword") String keyword);

    List<Buyer> findByCountryIgnoreCase(String country);

    List<Buyer> findByClassificationIgnoreCase(String classification);

    List<Buyer> findByEmailStatusIgnoreCase(String emailStatus);

    List<Buyer> findBySourcePlatformIgnoreCase(String sourcePlatform);

    List<Buyer> findByBusinessTypeIgnoreCase(String businessType);

    List<Buyer> findByProductNameIgnoreCase(String productName);

    List<Buyer> findByClassificationIsNull();

    boolean existsByWebsiteIgnoreCase(String website);

    @Query("SELECT b.website FROM Buyer b WHERE b.website IS NOT NULL")
    Set<String> findAllWebsiteValues();

    @Query("SELECT b.email FROM Buyer b WHERE b.email IS NOT NULL")
    Set<String> findAllEmailValues();

    @Query("SELECT b.website FROM Buyer b WHERE b.website LIKE '%linkedin.com%' OR b.website LIKE '%facebook.com%'")
    Set<String> findSocialWebsiteValues();

    @Query("SELECT COUNT(b) FROM Buyer b WHERE b.email IS NOT NULL AND (b.emailStatus IS NULL OR b.emailStatus <> 'CONTACTED')")
    long countPendingContactable();
}
