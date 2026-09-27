package interview.guide.modules.knowledgebase.model;

/**
 * 问答会话的检索范围类型。
 * <p>
 * 新范围类型（按标签、按题源等）只需要在这里加一个枚举值 + 一个解析器实现，
 * 检索层与前端都不用改。
 * </p>
 */
public enum RagScopeKind {

    /** 全部资料（检索不到范围限制） */
    ALL,

    /** 指定分类下的资料 */
    CATEGORY,

    /** 会话显式勾选的资料 */
    SELECTED
}
