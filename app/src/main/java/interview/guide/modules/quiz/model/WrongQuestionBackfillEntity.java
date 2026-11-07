package interview.guide.modules.quiz.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 错题本历史回填完成标记，避免每次打开空列表都重扫历史。
 */
@Entity
@Table(name = "wrong_question_backfills")
@Getter
@Setter
public class WrongQuestionBackfillEntity {

  @Id
  @Column(name = "owner_id")
  private Long ownerId;

  @Column(name = "backfilled_at", nullable = false)
  private LocalDateTime backfilledAt;

  public WrongQuestionBackfillEntity() {
  }

  public WrongQuestionBackfillEntity(Long ownerId, LocalDateTime backfilledAt) {
    this.ownerId = ownerId;
    this.backfilledAt = backfilledAt;
  }
}
