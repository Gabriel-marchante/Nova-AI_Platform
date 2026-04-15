-- Add new columns to users
ALTER TABLE users ADD COLUMN email VARCHAR(255) UNIQUE;
ALTER TABLE users ADD COLUMN profile_picture TEXT;
ALTER TABLE users ADD COLUMN openai_key_enc VARCHAR(500);
ALTER TABLE users ADD COLUMN anthropic_key_enc VARCHAR(500);
ALTER TABLE users ADD COLUMN role VARCHAR(50) DEFAULT 'USER';

-- Make gemini_key_enc nullable since you can use other keys now
ALTER TABLE users ALTER COLUMN gemini_key_enc DROP NOT NULL;

-- Create SystemError table
CREATE TABLE system_errors (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    error_message TEXT NOT NULL,
    error_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    occurrence_count INT NOT NULL DEFAULT 1
);

-- Create McpServerEntity table
CREATE TABLE mcp_servers (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(500) UNIQUE NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    registered_by_id UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
