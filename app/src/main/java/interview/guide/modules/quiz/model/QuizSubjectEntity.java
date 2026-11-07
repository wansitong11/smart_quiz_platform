package interview.guide.modules.quiz.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 用户自定义的测验科目。系统科目不落库，运行时与 classpath 预设合并。
 */
@Entity
@Table(name = "quiz_subjects", indexes = {
    @Index(name = "idx_quiz_subjects_owner_code", columnList = "owner_id,subject_code", unique = true),
    @Index(name = "idx_quiz_subjects_owner_exam", columnList = "owner_id,exam_code")
})
@Getter
@Setter
public class QuizSubjectEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "subject_code", nullable = false, length = 64)
  private String subjectCode;

  @Column(name = "owner_id", nullable = false)
  private Long ownerId;

  @Column(name = "exam_code", nullable = false, length = 64)
  private String examCode;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
  private String configJson;

  @Column(name = "deleted_at")
  private LocalDateTime deletedAt;

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
}
