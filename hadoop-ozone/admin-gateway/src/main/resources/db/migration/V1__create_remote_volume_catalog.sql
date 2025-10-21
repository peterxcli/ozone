-- Remote Volume Catalog Schema
-- Version: 1.0
-- Description: Creates the remote_volume_catalog table for tracking JuiceFS remote volumes

CREATE TABLE IF NOT EXISTS remote_volume_catalog (
    id VARCHAR(255) PRIMARY KEY,
    remote_volume_name VARCHAR(255) NOT NULL,
    owner_id VARCHAR(255) NOT NULL,
    bucket_name VARCHAR(255) NOT NULL,
    metadata_store_source VARCHAR(50) NOT NULL,
    metadata_connection_string TEXT NOT NULL,
    access_key VARCHAR(255),
    secret_key_hash VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    additional_info TEXT,

    CONSTRAINT chk_metadata_store_source
        CHECK (metadata_store_source IN ('INTERNAL', 'EXTERNAL', 'MANAGED')),
    CONSTRAINT chk_status
        CHECK (status IN ('active', 'inactive', 'error', 'deleting'))
);

-- Create index on owner_id for faster lookups
CREATE INDEX IF NOT EXISTS idx_remote_volume_owner_id
    ON remote_volume_catalog (owner_id);

-- Create index on remote_volume_name for uniqueness check
CREATE INDEX IF NOT EXISTS idx_remote_volume_name
    ON remote_volume_catalog (remote_volume_name);

-- Create index on status for filtering
CREATE INDEX IF NOT EXISTS idx_remote_volume_status
    ON remote_volume_catalog (status);

-- Create index on created_at for time-based queries
CREATE INDEX IF NOT EXISTS idx_remote_volume_created_at
    ON remote_volume_catalog (created_at);
