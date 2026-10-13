import { request } from './request';
import { applyScope, type DataScopeParam } from './scope';
import type {
  BatchSubmitAnswerItem,
  BatchSubmitAnswerResponse,
  CreateInterviewRequest,
  CurrentQuestionResponse,
  InterviewReport,
  InterviewSession,
  SubmitAnswerRequest,
  SubmitAnswerResponse
} from '../types/interview';

export interface TextSessionMeta {
  sessionId: string;
  skillId: string;
  difficulty: string;
  resumeId: number | null;
  totalQuestions: number;
  status: string;
  evaluateStatus: string | null;
  evaluateError: string | null;
  overallScore: number | null;
  sourceType: string | null;
  knowledgeBaseId: number | null;
  subjectCategory?: string | null;
  createdAt: string;
  completedAt: string | null;
}

export const interviewApi = {
  /**
   * 列出文字面试会话
   *
   * @param sourceTypes 可选：按来源过滤（如 "QUIZ,QUIZ_WRONG"），不传返回全部
   * @param scope 管理员传 'all' 时查看全部用户的会话
   */
  async listSessions(sourceTypes?: string, scope?: DataScopeParam): Promise<TextSessionMeta[]> {
    const params = new URLSearchParams();
    if (sourceTypes) {
      params.append('sourceTypes', sourceTypes);
    }
    applyScope(params, scope);
    const query = params.toString();
    return request.get<TextSessionMeta[]>(`/api/interview/sessions${query ? `?${query}` : ''}`);
  },

  /**
   * 创建面试会话
   */
  async createSession(req: CreateInterviewRequest): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/interview/sessions', req, {
      timeout: 180000, // 3分钟超时，AI生成问题需要时间
    });
  },

  /**
   * 获取会话信息
   */
  async getSession(sessionId: string): Promise<InterviewSession> {
    return request.get<InterviewSession>(`/api/interview/sessions/${sessionId}`);
  },

  /**
   * 从这次试卷练习再开一次作答（报告页「再练一遍」）。
   * 未交卷则返回那一次，已交卷则基于同一份切题结果新开一次。
   */
  async startNewAttempt(sessionId: string): Promise<InterviewSession> {
    return request.post<InterviewSession>(`/api/interview/sessions/${sessionId}/attempts`);
  },

  /**
   * 获取当前问题
   */
  async getCurrentQuestion(sessionId: string): Promise<CurrentQuestionResponse> {
    return request.get<CurrentQuestionResponse>(`/api/interview/sessions/${sessionId}/question`);
  },

  /**
   * 提交答案
   */
  async submitAnswer(req: SubmitAnswerRequest): Promise<SubmitAnswerResponse> {
    return request.post<SubmitAnswerResponse>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      { questionIndex: req.questionIndex, answer: req.answer },
      {
        timeout: 180000, // 3分钟超时
      }
    );
  },

  /**
   * 获取面试报告
   */
  async getReport(sessionId: string): Promise<InterviewReport> {
    return request.get<InterviewReport>(`/api/interview/sessions/${sessionId}/report`, {
      timeout: 180000, // 3分钟超时，AI评估需要时间
    });
  },

  /**
   * 查找未完成的面试会话
   */
  async findUnfinishedSession(resumeId: number): Promise<InterviewSession | null> {
    try {
      return await request.get<InterviewSession>(`/api/interview/sessions/unfinished/${resumeId}`);
    } catch {
      // 如果没有未完成的会话，返回null
      return null;
    }
  },

  /**
   * 暂存答案（不进入下一题）
   */
  async saveAnswer(req: SubmitAnswerRequest): Promise<void> {
    return request.put<void>(
      `/api/interview/sessions/${req.sessionId}/answers`,
      { questionIndex: req.questionIndex, answer: req.answer }
    );
  },

  /**
   * 提前交卷
   */
  async completeInterview(sessionId: string): Promise<void> {
    return request.post<void>(`/api/interview/sessions/${sessionId}/complete`);
  },

  /**
   * 整卷交卷：一次提交整卷答案，后端批量落库并触发批改
   * 适用于可跳题、可回改的试卷 / 练习材料作答
   */
  async submitWholePaper(
    sessionId: string,
    answers: BatchSubmitAnswerItem[]
  ): Promise<BatchSubmitAnswerResponse> {
    return request.post<BatchSubmitAnswerResponse>(
      `/api/interview/sessions/${sessionId}/answers/batch`,
      { answers },
      {
        timeout: 180000, // 3分钟超时
      }
    );
  },
};
