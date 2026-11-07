ALTER TABLE practice_answers
  ADD COLUMN score_status VARCHAR(20),
  ADD COLUMN low_confidence BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN answer_key_confidence INTEGER,
  ADD COLUMN score_message TEXT;
