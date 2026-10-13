package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资料库访问权限的唯一入口：把"能不能读 / 能不能维护"的判定收在一处，
 * 避免各 Service 各写一遍（写错一处就是一个越权口子）。
 *
 * <ul>
 *   <li><b>可读</b>：自己的资料 + 全局共享资料（管理员 {@code ?scope=all} 时不过滤）；</li>
 *   <li><b>可维护</b>：归属人本人，或（共享资料 且 管理员）；{@code ?scope=all} 不影响写权限。</li>
 * </ul>
 *
 * 两种情况都按"不存在"抛 404，不暴露资源存在性。
 * 只供**请求线程**使用；异步消费者/调度器没有登录上下文，请按 id 直查。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessService {

  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final OwnerScope ownerScope;

  /** 取可维护的资料（改分类 / 重新向量化 / 删除 / 题库写）；否则 404 */
  @Transactional(readOnly = true)
  public KnowledgeBaseEntity requireManageable(Long id) {
    return knowledgeBaseRepository
        .findByIdManageable(id, ownerScope.optionalOwnerId(), ownerScope.isAdmin())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在或无权访问"));
  }

  /** 取可读的资料（详情 / 生成状态这类只读入口）；否则 404 */
  @Transactional(readOnly = true)
  public KnowledgeBaseEntity requireReadable(Long id) {
    return knowledgeBaseRepository
        .findByIdAccessible(id, ownerScope.effectiveOwnerId())
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在或无权访问"));
  }
}
