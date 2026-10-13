package interview.guide.common.model;

import interview.guide.common.constant.CommonConstants.InterviewDefaults;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 题目难度三档。
 *
 * <p>编码沿用历史的 {@code junior / mid / senior}（库里存量数据就是这个值，不做迁移），
 * 对外展示统一用「简单 / 中等 / 偏难」。
 *
 * <p>描述刻意**不绑定学段**：出题的深浅应当跟着资料/学科知识板块本身的学段走
 * （小学资料就出小学难度、高中资料才出高中难度），所以这里只描述"认知要求"，
 * 不写"高考水平""合格考水平"这类字样。
 */
public enum DifficultyLevel {

  EASY("junior", "简单", "直接考查基本概念与基本方法，一步即可作答，不设陷阱"),
  MEDIUM("mid", "中等", "需要两步以上推理，或对概念做简单变形与迁移"),
  HARD("senior", "偏难", "需要跨知识点综合、分类讨论或严谨的推理过程");

  private static final Map<String, DifficultyLevel> BY_CODE = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(DifficultyLevel::code, Function.identity()));

  /** 中文别名（用户/前端直接用中文传值时也能识别） */
  private static final Map<String, DifficultyLevel> BY_LABEL = Arrays.stream(values())
      .collect(Collectors.toUnmodifiableMap(DifficultyLevel::label, Function.identity()));

  private final String code;
  private final String label;
  private final String description;

  DifficultyLevel(String code, String label, String description) {
    this.code = code;
    this.label = label;
    this.description = description;
  }

  public String code() {
    return code;
  }

  public String label() {
    return label;
  }

  public String description() {
    return description;
  }

  /** 编码 → 档位；未知编码返回 null */
  public static DifficultyLevel fromCode(String code) {
    return code == null ? null : BY_CODE.get(code.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * 归一化难度值：接受编码（junior/mid/senior）、等价英文（easy/medium/hard）
   * 与中文（简单/中等/偏难），统一落成库里的编码；识别不了时回落到默认「中等」。
   */
  public static String normalize(String value) {
    if (value == null || value.isBlank()) {
      return InterviewDefaults.DIFFICULTY;
    }
    String trimmed = value.trim();
    DifficultyLevel level = fromCode(trimmed);
    if (level == null) {
      level = BY_LABEL.get(trimmed);
    }
    if (level == null) {
      level = switch (trimmed.toLowerCase(Locale.ROOT)) {
        case "easy", "basic" -> EASY;
        case "medium", "normal" -> MEDIUM;
        case "hard", "difficult", "advanced" -> HARD;
        default -> null;
      };
    }
    return level == null ? trimmed : level.code();
  }

  /** 供 Prompt 使用的中文难度描述（含档位名与认知要求） */
  public static String describe(String value) {
    DifficultyLevel level = fromCode(normalize(value));
    DifficultyLevel resolved = level == null ? MEDIUM : level;
    return resolved.label + "（" + resolved.description + "）";
  }

  /** 展示用标签；识别不了时原样返回，避免把已有数据吞掉 */
  public static String labelOf(String value) {
    DifficultyLevel level = fromCode(normalize(value));
    return level == null ? value : level.label;
  }
}
