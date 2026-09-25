-- V4: Add campaign_responses and leads tables for Leads & Responses feature

CREATE TABLE IF NOT EXISTS campaign_responses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    buyer_id BIGINT NULL,
    campaign_id BIGINT NULL,
    email VARCHAR(255) NOT NULL,
    buyer_name VARCHAR(255),
    company_name VARCHAR(255),
    response_type VARCHAR(50) DEFAULT 'REPLY',
    subject VARCHAR(500),
    body_preview TEXT,
    response_date DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_cr_buyer_id (buyer_id),
    INDEX idx_cr_campaign_id (campaign_id),
    INDEX idx_cr_email (email),
    INDEX idx_cr_response_date (response_date)
);

CREATE TABLE IF NOT EXISTS leads (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    buyer_name VARCHAR(255),
    company_name VARCHAR(255),
    email VARCHAR(255),
    notes TEXT,
    lead_status VARCHAR(50) DEFAULT 'NEW',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_lead_status (lead_status),
    INDEX idx_lead_email (email)
);
