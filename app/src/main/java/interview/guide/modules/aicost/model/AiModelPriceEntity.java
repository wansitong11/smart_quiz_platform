package interview.guide.modules.aicost.model;

import interview.guide.modules.aicost.PriceSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_model_prices")
public class AiModelPriceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String provider;

  @Column(nullable = false, length = 128)
  private String model;

  @Column(nullable = false, length = 16)
  private String modality;

  @Column(name = "input_price_per_1m")
  private BigDecimal inputPricePer1m;

  @Column(name = "output_price_per_1m")
  private BigDecimal outputPricePer1m;

  @Column(name = "cached_price_per_1m")
  private BigDecimal cachedPricePer1m;

  @Column(nullable = false, length = 8)
  private String unit;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private PriceSource source;

  @Column(name = "effective_from", nullable = false)
  private Instant effectiveFrom;

  @Column(name = "effective_to")
  private Instant effectiveTo;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    createdAt = now;
    updatedAt = now;
    if (unit == null) {
      unit = "TOKEN";
    }
    if (currency == null) {
      currency = "CNY";
    }
    if (source == null) {
      source = PriceSource.AUTO;
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
