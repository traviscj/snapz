CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  principal VARCHAR(255) NOT NULL,
  default_vault_id BIGINT NULL,
  created_at_ms BIGINT NOT NULL,
  UNIQUE KEY u_principal (principal)
)
