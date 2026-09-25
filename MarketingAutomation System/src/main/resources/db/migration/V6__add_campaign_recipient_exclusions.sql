-- V6: Add campaign_recipient_exclusions table.
-- Tracks buyers permanently removed from a specific campaign's recipient list
-- (keyed by campaign_id + buyer_id). Other campaigns and the global buyer
-- database remain untouched.

CREATE TABLE IF NOT EXISTS campaign_recipient_exclusions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT NOT NULL,
    buyer_id BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_cre_campaign_buyer (campaign_id, buyer_id),
    INDEX idx_cre_campaign_id (campaign_id),
    INDEX idx_cre_buyer_id (buyer_id)
);