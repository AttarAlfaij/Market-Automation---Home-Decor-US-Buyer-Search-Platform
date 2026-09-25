package com.exportautomation.repository;

import com.exportautomation.entity.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {

    List<Lead> findAllByOrderByCreatedAtDesc();

    List<Lead> findByLeadStatusIgnoreCaseOrderByCreatedAtDesc(String leadStatus);

    @Query("SELECT COUNT(l) FROM Lead l")
    long countAllLeads();

    @Query("SELECT l FROM Lead l WHERE " +
           "LOWER(l.buyerName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(l.companyName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(l.email) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<Lead> searchByKeyword(String keyword);
}
