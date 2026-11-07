package interview.guide.modules.aicost.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_user_rate_limit_override")
public class AiUserRateLimitOverrideEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(length = 32)
  private String feature;

  @Column(name = "rpm_limit")
  private Integer rpmLimit;

  @Column(name = "tpm_limit")
  private Long tpmLimit;

  @Column(name = "concurrency_limit")
  private Integer concurrencyLimit;

  @Column(name = "effective_from", nullable = false)
  private Instant effectiveFrom;

  @Column(name = "effective_to")
  private Instant effectiveTo;
}
