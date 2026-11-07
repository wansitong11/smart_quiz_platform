-- 识别失败诊断：题干图一致性等失败时落结构化诊断，避免靠猜测定位

ALTER TABLE paper_pages
  ADD COLUMN diagnostic_json TEXT;
