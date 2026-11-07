package interview.guide.modules.aicost.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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
@Table(name = "ai_user_budget_override")
public class AiUserBudgetOverrideEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(length = 32)
  private String feature;

  @Column(nullable = false, length = 8)
  private String period;

  @Column(name = "limit_type", nullable = false, length = 8)
  private String limitType;

  @Column(name = "soft_limit")
  private BigDecimal softLimit;

  @Column(name = "hard_limit")
  private BigDecimal hardLimit;

  @Column(name = "effective_from", nullable = false)
  private Instant effectiveFrom;

  @Column(name = "effective_to")
  private Instant effectiveTo;
}
