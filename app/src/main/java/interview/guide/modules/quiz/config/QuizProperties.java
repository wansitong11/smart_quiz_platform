package interview.guide.modules.quiz.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模拟测验组卷参数（app.quiz.*）。
 * <p>
 * 这里的上限不只是"体验优化"，更是稳定性约束：单次模型调用越大，
 * 生成耗时越长，越容易撞上底层 HTTP 客户端 60 秒无响应就重置流的问题
 * （详见 docs/项目部署/登录鉴权与发布方案.md 的踩坑第 10 条）。
 */
@Component
@ConfigurationProperties(prefix = "app.quiz")
@Getter
@Setter
public class QuizProperties {

  /** 单次测验最大题量 */
  private int maxQuestions = 12;

  /** 单次测验最小题量 */
  private int minQuestions = 3;

  /** 单个知识板块下的最大题量（避免某一板块把整卷堆满） */
  private int maxQuestionsPerSection = 6;

  /** 单篇阅读材料最大字符数，超出截断（防止材料把 prompt 撑爆） */
  private int maxMaterialCharsPerMaterial = 1200;

  /** 整卷所有材料合计最大字符数 */
  private int maxTotalMaterialChars = 3600;

  /** 单个题干最大字符数，超出截断 */
  private int maxQuestionStemChars = 600;

  /** 单个选项最大字符数，超出截断 */
  private int maxOptionChars = 200;

  /** 默认分值：模型没给分值时按题型兜底 */
  private int defaultChoiceScore = 3;
  private int defaultFillScore = 3;
  private int defaultShortAnswerScore = 6;
  private int defaultReadingScore = 8;
  private int defaultWritingScore = 20;
  private int defaultOtherScore = 5;

  /** 组卷时可参考的历史题数量（用于去重提示） */
  private int historyQuestionLimit = 20;
}
