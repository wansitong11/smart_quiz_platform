package interview.guide.modules.quiz.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 一次批改结果的逐题回写标记，保证异步任务重试不会重复累计掌握度。
 */
@Entity
@Table(name = "wrong_question_attempt_events", indexes = {
    @Index(name = "idx_wrong_attempt_event_session_question",
        columnList = "session_id,question_index", unique = true)
})
@Getter
@Setter
public class WrongQuestionAttemptEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "owner_id", nullable = false)
  private Long ownerId;

  @Column(name = "session_id", nullable = false, length = 36)
  private String sessionId;

  @Column(name = "question_index", nullable = false)
  private Integer questionIndex;

  @Column(name = "processed_at", nullable = false)
  private LocalDateTime processedAt;

  @PrePersist
  void onCreate() {
    if (processedAt == null) {
      processedAt = LocalDateTime.now();
    }
  }
}
