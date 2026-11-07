package interview.guide.modules.resume.model;

/**
 * 单个题干图声明锚点的匹配结论。
 *
 * <p>只有 {@link #MATCHED} 表示声明与最终 FIGURE/STEM 资源能唯一对齐；
 * 其余状态都会让该页识别失败，但文案与诊断要能指出具体是哪个锚点。</p>
 */
public enum StemFigureVerdict {

  /** 恰好一个最终资源锚点能对上该声明。 */
  MATCHED,

  /** 没有任何 FIGURE/STEM 资源的锚点能对上该声明。 */
  MISSING,

  /** 有多个不同资源锚点都能对上该声明，无法唯一归属。 */
  AMBIGUOUS
}
