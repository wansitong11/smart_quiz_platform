package interview.guide.modules.knowledgebase.service.scope;

import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagScopeKind;

/**
 * 会话检索范围解析器：把会话上声明的范围翻译成「本次检索用哪些资料」。
 * <p>
 * 每种 {@link RagScopeKind} 一个实现，新增范围类型不影响检索层。
 * </p>
 */
public interface RagScopeResolver {

    RagScopeKind kind();

    ResolvedScope resolve(RagChatSessionEntity session);
}
