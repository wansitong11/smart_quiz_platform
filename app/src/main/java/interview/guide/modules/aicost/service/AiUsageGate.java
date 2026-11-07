package interview.guide.modules.aicost.service;

import interview.guide.common.ai.AiFeature;
import interview.guide.common.ai.AiRateLimitExceededException;
import interview.guide.modules.aicost.service.AiUserRateLimitResolver.RateLimits;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiUsageGate {

  private static final long WINDOW_MS = 60_000L;

  private final RedissonClient redissonClient;
  private final AiAdminPolicy adminPolicy;
  private final AiUserRateLimitResolver rateLimitResolver;

  private RScript rScript;
  private String gateSha;
  private String releaseSha;

  @PostConstruct
  void init() throws Exception {
    rScript = redissonClient.getScript(StringCodec.INSTANCE);
    gateSha = rScript.scriptLoad(load("scripts/ai_usage_gate.lua"));
    releaseSha = rScript.scriptLoad(load("scripts/ai_usage_release.lua"));
  }

  /**
   * 尝试占用一次调用并发槽。
   *
   * @return true 表示已占用并发槽，调用方结束后必须释放；false 表示本次调用未占用，
   *     包括管理员豁免和系统/匿名调用
   */
  public boolean tryAcquire(AiFeature feature, Long userId, long estimateTokens) {
    if (adminPolicy.isAdmin(userId)) {
      return false;
    }
    RateLimits limits = rateLimitResolver.resolve(feature, userId);

    String bucket = userId == null ? "system" : userId.toString();
    List<Object> keys = List.of(
        gateKey("rpm", bucket, feature),
        gateKey("tpm", bucket, feature),
        gateKey("conc", bucket, feature)
    );
    Long result = evalSha(gateSha, keys,
        String.valueOf(limits.rpm()), String.valueOf(limits.tpm()),
        String.valueOf(limits.concurrency()),
        String.valueOf(WINDOW_MS), String.valueOf(Math.max(0, estimateTokens)));
    if (result != null && result == 0) {
      return true;
    }
    releaseConcurrency(bucket, feature);
    throw new AiRateLimitExceededException(
        "AI 请求过于频繁，请稍后重试", retryAfterFor(feature, userId));
  }

  private long retryAfterFor(AiFeature feature, Long userId) {
    return Math.max(0, remainingRpmWindowMillisIfExhausted(feature, userId));
  }

  /**
   * 当前分钟窗口的 RPM 已用满时，返回窗口剩余毫秒数；未用满或无法读取时返回 0。
   *
   * <p>调用方只把这个值用于等待窗口恢复，不直接修改闸门计数。真正的放行仍由
   * {@link #tryAcquire(AiFeature, Long, long)} 负责。</p>
   */
  public long remainingRpmWindowMillisIfExhausted(AiFeature feature, Long userId) {
    if (adminPolicy.isAdmin(userId)) {
      return 0;
    }
    RateLimits limits = rateLimitResolver.resolve(feature, userId);
    int rpm = limits.rpm();
    String bucketName = userId == null ? "system" : userId.toString();
    String key = gateKey("rpm", bucketName, feature);
    try {
      RBucket<String> bucket = redissonClient.getBucket(key, StringCodec.INSTANCE);
      String value = bucket.get();
      if (value == null) {
        return 0;
      }
      long used = Long.parseLong(value);
      if (used < rpm) {
        return 0;
      }
      long ttl = bucket.remainTimeToLive();
      return Math.max(0, ttl);
    } catch (Exception e) {
      log.warn("[AiUsageGate] 读取 RPM 窗口失败: feature={}, bucket={}", feature, bucketName, e);
      return 0;
    }
  }

  public void release(AiFeature feature, Long userId) {
    String bucket = userId == null ? "system" : userId.toString();
    releaseConcurrency(bucket, feature);
  }

  private void releaseConcurrency(String bucket, AiFeature feature) {
    try {
      evalSha(releaseSha, List.of(gateKey("conc", bucket, feature)));
    } catch (Exception e) {
      log.warn("[AiUsageGate] 释放并发计数失败: feature={}, bucket={}", feature, bucket, e);
    }
  }

  private Long evalSha(String sha, List<Object> keys, String... args) {
    try {
      Object result = rScript.evalSha(RScript.Mode.READ_WRITE, sha, RScript.ReturnType.VALUE,
          keys, (Object[]) args);
      return result instanceof Number n ? n.longValue() : null;
    } catch (org.redisson.client.RedisException e) {
      if (e.getMessage() != null && e.getMessage().contains("NOSCRIPT")) {
        initQuietly();
        Object result = rScript.evalSha(RScript.Mode.READ_WRITE, sha, RScript.ReturnType.VALUE,
            keys, (Object[]) args);
        return result instanceof Number n ? n.longValue() : null;
      }
      throw e;
    }
  }

  private void initQuietly() {
    try {
      init();
    } catch (Exception e) {
      log.warn("[AiUsageGate] 重新加载 Lua 脚本失败", e);
    }
  }

  private String gateKey(String metric, String bucket, AiFeature feature) {
    return "ai:gate:" + metric + ":{" + bucket + ":" + feature.name() + "}";
  }

  private static String load(String path) throws Exception {
    return new String(new ClassPathResource(path).getContentAsByteArray(), StandardCharsets.UTF_8);
  }
}
