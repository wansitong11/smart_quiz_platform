package interview.guide.common.aspect;

import interview.guide.common.ai.AiCallContextHolder;
import interview.guide.common.ai.AiCallContextHolder.AiCallContext;
import interview.guide.common.ai.AiCost;
import interview.guide.common.ai.AiFeature;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 把 {@link AiCost} 注解映射为线程级调用上下文，供计量 Advisor 与预算服务读取。
 */
@Slf4j
@Aspect
@Component
public class AiCostAspect {

  @Around("@annotation(interview.guide.common.ai.AiCost)")
  public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    AiCost annotation = ((MethodSignature) joinPoint.getSignature())
        .getMethod().getAnnotation(AiCost.class);
    AiFeature feature = annotation.feature();
    AiCallContext context = new AiCallContext(
        resolveUserId(),
        feature,
        annotation.operation(),
        UUID.randomUUID().toString(),
        "op-" + UUID.randomUUID(),
        "SYNC"
    );
    return AiCallContextHolder.callWith(context, joinPoint::proceed);
  }

  private Long resolveUserId() {
    ServletRequestAttributes attributes =
        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attributes == null) {
      return null;
    }
    HttpServletRequest request = attributes.getRequest();
    Object userId = request.getAttribute("userId");
    if (userId == null) {
      userId = request.getHeader("X-User-Id");
    }
    if (userId == null) {
      return null;
    }
    try {
      return Long.valueOf(userId.toString());
    } catch (NumberFormatException e) {
      log.debug("无法解析 userId: {}", userId);
      return null;
    }
  }
}
