-- Remote Volume Metrics Schema
-- Version: 2.0
-- Description: Adds metrics tracking columns for remote volumes

-- Add metrics columns to remote_volume_catalog
ALTER TABLE remote_volume_catalog
    ADD COLUMN last_accessed_at TIMESTAMP,
    ADD COLUMN access_count BIGINT DEFAULT 0,
    ADD COLUMN storage_used_bytes BIGINT DEFAULT 0,
    ADD COLUMN object_count BIGINT DEFAULT 0;

-- Create index on last_accessed_at for cleanup/archival queries
CREATE INDEX IF NOT EXISTS idx_remote_volume_last_accessed
    ON remote_volume_catalog (last_accessed_at);
