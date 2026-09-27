ALTER TABLE practice_sessions
    ADD COLUMN request_id VARCHAR(64);

CREATE UNIQUE INDEX uk_practice_sessions_request_id
    ON practice_sessions (request_id);
