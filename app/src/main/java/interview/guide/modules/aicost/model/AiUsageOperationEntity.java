package interview.guide.modules.aicost.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
@Table(name = "ai_usage_operations")
public class AiUsageOperationEntity {

  @Id
  @Column(name = "operation_id", length = 64)
  private String operationId;

  @Column(name = "request_id", length = 64)
  private String requestId;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  @Column(name = "user_id")
  private Long userId;

  @Column(nullable = false, length = 32)
  private String feature;

  @Column(nullable = false, length = 16)
  private String source;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "estimated_cost", nullable = false)
  private BigDecimal estimatedCost;

  @Column(name = "actual_cost")
  private BigDecimal actualCost;

  @Column(nullable = false, length = 3)
  private String currency;

  /** 发起时是否豁免额度（管理员/授权用户）；用于结算时跳过总量池。 */
  @Builder.Default
  @Column(nullable = false)
  private Boolean exempt = false;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "settled_at")
  private Instant settledAt;

  @PrePersist
  void prePersist() {
    if (tenantId == null) {
      tenantId = "default";
    }
    if (currency == null) {
      currency = "CNY";
    }
    if (startedAt == null) {
      startedAt = Instant.now();
    }
  }
}
