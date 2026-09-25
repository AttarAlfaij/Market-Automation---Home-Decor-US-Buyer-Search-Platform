ALTER TABLE buyers ADD COLUMN lead_priority VARCHAR(50) DEFAULT NULL AFTER classification;
ALTER TABLE buyers ADD COLUMN classification_reason TEXT DEFAULT NULL AFTER lead_priority;
