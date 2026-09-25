-- V3: Add campaign configuration fields for Step 5
-- Adds sender info, product details, and attachment path to campaigns table

ALTER TABLE campaigns
    ADD COLUMN product_category VARCHAR(100) DEFAULT NULL AFTER product_name,
    ADD COLUMN export_availability VARCHAR(50) DEFAULT NULL AFTER product_category,
    ADD COLUMN customization_options TEXT DEFAULT NULL AFTER export_availability,
    ADD COLUMN minimum_order_quantity VARCHAR(100) DEFAULT NULL AFTER customization_options,
    ADD COLUMN shipping_availability VARCHAR(50) DEFAULT NULL AFTER minimum_order_quantity,
    ADD COLUMN sender_name VARCHAR(150) DEFAULT NULL AFTER shipping_availability,
    ADD COLUMN sender_company VARCHAR(200) DEFAULT NULL AFTER sender_name,
    ADD COLUMN sender_email VARCHAR(200) DEFAULT NULL AFTER sender_company,
    ADD COLUMN sender_phone VARCHAR(50) DEFAULT NULL AFTER sender_email,
    ADD COLUMN attachment_path VARCHAR(500) DEFAULT NULL AFTER attachment_name;
