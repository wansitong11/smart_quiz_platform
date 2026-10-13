package interview.guide.modules.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 刷新令牌（白名单模式：只有库里存在且未撤销、未过期的才认）
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
public class RefreshTokenEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "token_id", nullable = false, unique = true, length = 64)
  private String tokenId;

  /** SHA-256(secret)，不存明文 */
  @Column(name = "token_hash", nullable = false, length = 128)
  private String tokenHash;

  @Column(name = "user_agent", length = 255)
  private String userAgent;

  @Column(name = "client_ip", length = 64)
  private String clientIp;

  @Column(name = "issued_at", nullable = false)
  private LocalDateTime issuedAt;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  /** 轮换后指向新令牌，便于排查令牌重放 */
  @Column(name = "replaced_by", length = 64)
  private String replacedBy;

  public boolean isUsable(LocalDateTime now) {
    return revokedAt == null && expiresAt.isAfter(now);
  }
}
