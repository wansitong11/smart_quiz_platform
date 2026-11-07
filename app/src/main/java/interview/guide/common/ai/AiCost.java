package interview.guide.common.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个业务入口的 AI 功能归属，由 {@code AiCostAspect} 写入调用上下文。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiCost {

  AiFeature feature();

  String operation() default "";
}
