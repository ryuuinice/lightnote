ALTER TABLE tbl_note
    ADD COLUMN content_format VARCHAR(16) NOT NULL DEFAULT 'HTML'
    AFTER content;
