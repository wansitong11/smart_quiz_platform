import { request } from './request';
import { streamSse } from './stream';

// ========== 类型定义 ==========

/** 会话检索范围：全部资料 / 指定分类 / 指定资料 */
export type RagScope = 'ALL' | 'CATEGORY' | 'SELECTED';

export interface RagChatSession {
  id: number;
  title: string;
  scope: RagScope;
  categoryCode: string | null;
  /** 后端算好的范围描述，如「全部资料」「分类：数学」 */
  scopeDescription: string;
  knowledgeBaseIds: number[];
  createdAt: string;
}

export interface RagChatSessionListItem {
  id: number;
  title: string;
  messageCount: number;
  scope: RagScope;
  categoryCode: string | null;
  scopeDescription: string;
  knowledgeBaseNames: string[];
  updatedAt: string;
  isPinned: boolean;
}

export interface RagChatMessage {
  id: number;
  type: 'user' | 'assistant';
  content: string;
  createdAt: string;
}

export interface KnowledgeBaseItem {
  id: number;
  name: string;
  originalFilename: string;
  fileSize: number;
  contentType: string;
  uploadedAt: string;
  lastAccessedAt: string;
  accessCount: number;
  questionCount: number;
}

export interface RagChatSessionDetail {
  id: number;
  title: string;
  scope: RagScope;
  categoryCode: string | null;
  scopeDescription: string;
  knowledgeBases: KnowledgeBaseItem[];
  messages: RagChatMessage[];
  createdAt: string;
  updatedAt: string;
}

/** 创建/切换范围时的入参 */
export interface RagScopeRequest {
  scope: RagScope;
  categoryCode?: string | null;
  knowledgeBaseIds?: number[];
}

// ========== API 函数 ==========

export const ragChatApi = {
  /**
   * 创建新会话（不传范围 = 全部资料）
   */
  async createSession(scope: RagScopeRequest, title?: string): Promise<RagChatSession> {
    return request.post<RagChatSession>('/api/rag-chat/sessions', {
      scope: scope.scope,
      categoryCode: scope.categoryCode ?? null,
      knowledgeBaseIds: scope.knowledgeBaseIds ?? [],
      title,
    });
  },

  /**
   * 获取会话列表
   */
  async listSessions(): Promise<RagChatSessionListItem[]> {
    return request.get<RagChatSessionListItem[]>('/api/rag-chat/sessions');
  },

  /**
   * 获取会话详情
   */
  async getSessionDetail(sessionId: number): Promise<RagChatSessionDetail> {
    return request.get<RagChatSessionDetail>(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 更新会话标题
   */
  async updateSessionTitle(sessionId: number, title: string): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/title`, { title });
  },

  /**
   * 更新会话检索范围
   */
  async updateScope(sessionId: number, scope: RagScopeRequest): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/scope`, {
      scope: scope.scope,
      categoryCode: scope.categoryCode ?? null,
      knowledgeBaseIds: scope.knowledgeBaseIds ?? [],
    });
  },

  /**
   * 切换会话置顶状态
   */
  async togglePin(sessionId: number): Promise<void> {
    return request.put(`/api/rag-chat/sessions/${sessionId}/pin`);
  },

  /**
   * 删除会话
   */
  async deleteSession(sessionId: number): Promise<void> {
    return request.delete(`/api/rag-chat/sessions/${sessionId}`);
  },

  /**
   * 发送消息（流式SSE）
   */
  async sendMessageStream(
    sessionId: number,
    question: string,
    onMessage: (chunk: string) => void,
    onComplete: () => void,
    onError: (error: Error) => void
  ): Promise<void> {
    return streamSse({
      url: `/api/rag-chat/sessions/${sessionId}/messages/stream`,
      init: {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
      },
      onMessage,
      onComplete,
      onError,
      parseMode: 'event',
      trimDataPrefixSpace: false,
      unescapeEscapedNewlines: true,
      dataJoiner: '',
    });
  },
};
