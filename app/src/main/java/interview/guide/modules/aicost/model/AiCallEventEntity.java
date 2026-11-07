package interview.guide.modules.aicost.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
@Table(name = "ai_call_events")
public class AiCallEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "request_id", nullable = false, length = 64)
  private String requestId;

  @Column(name = "trace_id", length = 64)
  private String traceId;

  @Column(name = "operation_id", nullable = false, length = 64)
  private String operationId;

  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  @Column(name = "user_id")
  private Long userId;

  @Column(nullable = false, length = 32)
  private String feature;

  @Column(nullable = false, length = 32)
  private String operation;

  @Column(nullable = false, length = 16)
  private String source;

  @Column(nullable = false, length = 64)
  private String provider;

  @Column(nullable = false, length = 128)
  private String model;

  @Column(nullable = false, length = 16)
  private String modality;

  @Column(nullable = false, length = 24)
  private String status;

  @Column(name = "error_class", length = 64)
  private String errorClass;

  @Column(name = "retry_index", nullable = false)
  private int retryIndex;

  @Column(name = "latency_ms")
  private Long latencyMs;

  @Column(name = "queue_wait_ms")
  private Long queueWaitMs;

  @Column(name = "input_tokens")
  private Long inputTokens;

  @Column(name = "output_tokens")
  private Long outputTokens;

  @Column(name = "cached_tokens")
  private Long cachedTokens;

  @Column(name = "image_count")
  private Integer imageCount;

  @Column(name = "char_count")
  private Long charCount;

  @Column(name = "audio_seconds")
  private BigDecimal audioSeconds;

  @Column(name = "estimated_cost")
  private BigDecimal estimatedCost;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "price_version", length = 32)
  private String priceVersion;

  @Column(nullable = false)
  private boolean estimated;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    if (occurredAt == null) {
      occurredAt = createdAt;
    }
    if (tenantId == null) {
      tenantId = "default";
    }
    if (currency == null) {
      currency = "USD";
    }
  }
}
