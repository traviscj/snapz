UPDATE feed_cursors SET
  token = NULLIF(position, 0),
  created_at = TIMESTAMPADD(MICROSECOND, updated_at_ms * 1000, '1970-01-01 00:00:00'),
  updated_at = TIMESTAMPADD(MICROSECOND, updated_at_ms * 1000, '1970-01-01 00:00:00'),
  last_advanced_at = IF(position > 0, TIMESTAMPADD(MICROSECOND, updated_at_ms * 1000, '1970-01-01 00:00:00'), NULL)
