CREATE TABLE invites (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  email VARCHAR(255) NOT NULL,
  name VARCHAR(255) NULL,
  vault_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  invited_by VARCHAR(255) NOT NULL,
  idp_user_id VARCHAR(64) NULL,
  created_at_ms BIGINT NOT NULL,
  expires_at_ms BIGINT NOT NULL,
  accepted_at_ms BIGINT NULL,
  UNIQUE KEY u_code (code),
  KEY idx_email (email),
  KEY idx_vault (vault_id)
)
