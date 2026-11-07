package interview.guide.modules.quiz.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 用户错题本条目。按 owner_id + question_key 聚合。
 */
@Entity
@Table(name = "wrong_questions", indexes = {
    @Index(name = "idx_wrong_questions_owner_key",
        columnList = "owner_id,question_key", unique = true),
    @Index(name = "idx_wrong_questions_owner_status", columnList = "owner_id,status"),
    @Index(name = "idx_wrong_questions_owner_subject", columnList = "owner_id,subject_name"),
    @Index(name = "idx_wrong_questions_owner_knowledge", columnList = "owner_id,knowledge_point_key")
})
@Getter
@Setter
public class WrongQuestionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "owner_id", nullable = false)
  private Long ownerId;

  @Column(name = "question_key", nullable = false, length = 64)
  private String questionKey;

  @Column(name = "skill_id", nullable = false, length = 64)
  private String skillId;

  @Column(name = "exam_code", length = 64)
  private String examCode;

  @Column(name = "exam_name", length = 64)
  private String examName;

  @Column(name = "subject_name", length = 64)
  private String subjectName;

  @Column(name = "knowledge_point_key", length = 64)
  private String knowledgePointKey;

  @Column(name = "knowledge_point_name", length = 64)
  private String knowledgePointName;

  @Column(name = "question_type", length = 32)
  private String questionType;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String stem;

  @Column(name = "question_snapshot", nullable = false, columnDefinition = "TEXT")
  private String questionSnapshot;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private WrongQuestionStatus status = WrongQuestionStatus.PENDING;

  @Column(name = "correct_count", nullable = false)
  private int correctCount;

  @Column(name = "wrong_count", nullable = false)
  private int wrongCount;

  @Column(name = "last_score_rate")
  private Integer lastScoreRate;

  @Column(name = "last_user_answer", columnDefinition = "TEXT")
  private String lastUserAnswer;

  @Column(name = "last_wrong_at")
  private LocalDateTime lastWrongAt;

  @Column(name = "last_correct_at")
  private LocalDateTime lastCorrectAt;

  @Column(name = "mastered_at")
  private LocalDateTime masteredAt;

  @Column(name = "archived_at")
  private LocalDateTime archivedAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  public void recordCorrect(LocalDateTime answeredAt) {
    correctCount = Math.min(3, correctCount + 1);
    lastCorrectAt = answeredAt;
    if (correctCount >= 3) {
      status = WrongQuestionStatus.MASTERED;
      masteredAt = answeredAt;
    }
  }

  public void recordWrong(LocalDateTime answeredAt) {
    wrongCount++;
    correctCount = 0;
    status = WrongQuestionStatus.PENDING;
    masteredAt = null;
    archivedAt = null;
    lastWrongAt = answeredAt;
  }

  public void archive(LocalDateTime archivedAt) {
    status = WrongQuestionStatus.ARCHIVED;
    this.archivedAt = archivedAt;
  }

  public void restore() {
    status = WrongQuestionStatus.PENDING;
    archivedAt = null;
    masteredAt = null;
  }
}
