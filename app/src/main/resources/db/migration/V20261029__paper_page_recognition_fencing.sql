-- 单卷内页级并行识别：每页独立执行代次，终态写入必须匹配当前代次

ALTER TABLE paper_pages
  ADD COLUMN recognition_attempt_id VARCHAR(36);

CREATE INDEX idx_paper_pages_recognition_attempt
  ON paper_pages (paper_id, status, recognition_attempt_id);
