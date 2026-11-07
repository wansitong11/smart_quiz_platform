package interview.guide.modules.aicost.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全局月度总额度配置（单行，id=1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_quota_config")
public class AiQuotaConfigEntity {

  @Id
  private Long id;

  @Column(name = "monthly_total_cny", nullable = false)
  private BigDecimal monthlyTotalCny;

  @Column(name = "updated_by")
  private Long updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = Instant.now();
    if (id == null) {
      id = 1L;
    }
  }
}
