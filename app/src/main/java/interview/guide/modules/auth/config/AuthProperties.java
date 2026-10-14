package interview.guide.modules.auth.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录鉴权配置（app.auth.*）
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
@Getter
@Setter
public class AuthProperties {

  /** JWT 签名密钥（HS256，至少 32 字节）。生产必须通过 APP_AUTH_JWT_SECRET 注入 */
  private String secret;

  /**
   * 签发者标识。
   * <p>注意：Spring Security 读取 iss 声明时（{@code Jwt#getIssuer()}）会把它当 URL 解析，
   * 因此这里必须是 URL 形态的标识，不能写成普通的服务名，否则校验会抛
   * "Unable to convert claim 'iss' of type String to URL"。
   */
  private String issuer = "https://smart-quiz-platform";

  /** Access Token 有效期 */
  private Duration accessTokenTtl = Duration.ofHours(2);

  /** Refresh Token 有效期 */
  private Duration refreshTokenTtl = Duration.ofDays(30);

  /** 刷新令牌 Cookie 名 */
  private String refreshCookieName = "sq_refresh";

  /** 刷新令牌 Cookie 的 Path，限制只在鉴权接口上发送 */
  private String refreshCookiePath = "/api/auth";

  /** 生产为 https 时必须为 true；本地 http 调试时置 false */
  private boolean cookieSecure = true;

  /** Lax 即可：同源部署下足够，且能防跨站提交 */
  private String cookieSameSite = "Lax";

  /** Swagger 是否匿名开放；生产建议 false */
  private boolean swaggerPublic = false;

  /** 初始管理员用户名（仅当用户表为空时创建） */
  private String bootstrapAdminUsername = "admin";

  /** 初始管理员密码；留空则随机生成并打印到日志 */
  private String bootstrapAdminPassword;

  /** 登录失败限流（每 IP 每分钟次数） */
  private int loginRateLimitPerMinute = 10;

  /**
   * 是否开放自助注册。
   * <p>开放后任何人都能创建账号并使用 AI 能力（消耗你的额度），
   * 生产环境如果只给少数人用，建议置 false 改为管理员建号。
   */
  private boolean registrationEnabled = true;

  /** 同一来源 IP 24 小时内最多成功注册几个账号（0 或负数表示不限制） */
  private int registerMaxPerIpPerDay = 3;

  /** 全站每天最多成功自助注册几个账号（0 或负数表示不限制） */
  private int registerMaxGlobalPerDay = 5;

  public long accessTokenTtlSeconds() {
    return accessTokenTtl.toSeconds();
  }
}
