package interview.guide.modules.auth.security;

import interview.guide.modules.auth.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Access Token 的签发与校验（HS256 对称签名）。
 * <p>Refresh Token 不走 JWT，是随机串 + 数据库白名单，见 {@link RefreshTokenService}。
 */
@Service
public class JwtTokenService {

  /** Access Token 类型标记，防止 Refresh Token 被当成访问凭证 */
  public static final String TOKEN_TYPE_ACCESS = "access";

  private static final int MIN_SECRET_BYTES = 32;

  private final AuthProperties properties;
  private final JwtEncoder jwtEncoder;
  private final JwtDecoder jwtDecoder;

  public JwtTokenService(AuthProperties properties) {
    this.properties = properties;
    SecretKey key = buildSecretKey(properties.getSecret());
    this.jwtEncoder = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
    this.jwtDecoder = NimbusJwtDecoder.withSecretKey(key)
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
  }

  private static SecretKey buildSecretKey(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "缺少 JWT 签名密钥：请设置环境变量 APP_AUTH_JWT_SECRET（至少 32 字节随机串）");
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "APP_AUTH_JWT_SECRET 长度不足：HS256 要求至少 " + MIN_SECRET_BYTES + " 字节，当前 " + bytes.length + " 字节");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  public String createAccessToken(Long userId, String username, Integer tokenVersion,
      List<String> roles, List<String> permissions) {
    Instant now = Instant.now();
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(properties.getIssuer())
        .issuedAt(now)
        .expiresAt(now.plus(properties.getAccessTokenTtl()))
        .subject(String.valueOf(userId))
        .id(UUID.randomUUID().toString())
        .claim("typ", TOKEN_TYPE_ACCESS)
        .claim("ver", tokenVersion)
        .claim("username", username)
        .claim("roles", roles)
        .claim("perms", permissions)
        .build();

    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
  }

  /**
   * 校验签名与过期时间。
   *
   * @throws JwtException 签名不合法或已过期
   */
  public Jwt decode(String token) {
    Jwt jwt = jwtDecoder.decode(token);
    // 用原始 claim 字符串比对，避免 Jwt#getIssuer() 把 iss 强制解析为 URL 带来的耦合
    Object issuer = jwt.getClaims().get("iss");
    if (!properties.getIssuer().equals(issuer == null ? null : issuer.toString())) {
      throw new JwtException("issuer 不匹配");
    }
    return jwt;
  }

  public boolean isAccessToken(Jwt jwt) {
    return TOKEN_TYPE_ACCESS.equals(jwt.getClaimAsString("typ"));
  }

  public long accessTokenTtlSeconds() {
    return properties.accessTokenTtlSeconds();
  }
}
