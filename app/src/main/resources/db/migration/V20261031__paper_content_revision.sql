ALTER TABLE practice_papers
    ADD COLUMN IF NOT EXISTS content_revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE practice_sessions
    ADD COLUMN IF NOT EXISTS paper_content_revision BIGINT;

UPDATE practice_sessions s
SET paper_content_revision = p.content_revision
FROM practice_papers p
WHERE s.resume_id = p.id
  AND s.source_type = 'PAPER'
  AND s.paper_content_revision IS NULL;

ALTER TABLE paper_assets
    ADD COLUMN IF NOT EXISTS source_attempt_id VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_paper_asset_source_attempt
    ON paper_assets (paper_id, page_no, source_attempt_id);
