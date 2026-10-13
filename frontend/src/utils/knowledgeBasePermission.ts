import type { KnowledgeBaseItem } from '../api/knowledgebase';
import type { CurrentUser } from '../types/auth';

/**
 * 能否**维护**这份资料：改分类 / 重新向量化 / 删除 / 题库写（出题·编辑·删除·启停）。
 *
 * - 共享资料（SHARED）：只有管理员能维护——共享资料是公共资源，谁上传的不重要；
 * - 私有资料：只有归属人本人。
 *
 * 后端有同一套判定（`KnowledgeBaseAccessService.requireManageable`，越权一律 404），
 * 这里只负责不渲染注定失败的入口。可读性（查看/下载/检索/开考）不受影响。
 */
export function canManageKnowledgeBase(
  kb: Pick<KnowledgeBaseItem, 'ownerId' | 'visibility'> | null | undefined,
  user: CurrentUser | null,
): boolean {
  if (!kb) {
    return false;
  }
  if (kb.visibility === 'SHARED') {
    return Boolean(user?.roles?.includes('ROLE_ADMIN'));
  }
  return kb.ownerId != null && kb.ownerId === user?.id;
}
