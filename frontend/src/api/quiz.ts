import { request } from './request';
import type {
  BatchSubmitAnswerItem,
  BatchSubmitAnswerResponse,
  InterviewSession
} from '../types/interview';
import type { InterviewDetail } from './history';

/** 学科知识板块（来自 skill.meta.yml） */
export interface QuizSubjectCategory {
  key: string;
  label: string;
  priority?: string | null;
}

/** 学科（语数英） */
export interface QuizSubject {
  id: string;
  name: string;
  description?: string | null;
  categories?: QuizSubjectCategory[] | null;
  isPreset?: boolean;
  display?: {
    icon?: string | null;
    gradient?: string | null;
    iconBg?: string | null;
    iconColor?: string | null;
  } | null;
}

/** 组卷参数 */
export interface QuizConfigRequest {
  skillId: string;
  difficulty?: string;
  questionCount?: number;
  categories?: string[];
  questionTypes?: string[];
  llmProvider?: string;
}

/** 测验记录条目 */
export interface QuizSessionMeta {
  sessionId: string;
  skillId: string;
  difficulty: string;
  totalQuestions: number;
  status: string;
  evaluateStatus: string | null;
  evaluateError: string | null;
  overallScore: number | null;
  sourceType: string | null;
  subjectCategory?: string | null;
  createdAt: string;
  completedAt: string | null;
}

/** 错题本条目 */
export interface WrongQuestionItem {
  sessionId: string;
  questionIndex: number;
  skillId: string;
  category: string | null;
  questionType: string | null;
  stem: string;
  userAnswer: string | null;
  referenceAnswer: string | null;
  analysis: string | null;
  score: number | null;
  fullScore: number | null;
  wrongCount: number;
  lastWrongAt: string | null;
}

export interface WrongQuestionRef {
  sessionId: string;
  questionIndex: number;
}

export const quizApi = {
  /** 学科与知识板块 */
  async listSubjects(): Promise<QuizSubject[]> {
    return request.get<QuizSubject[]>('/api/quiz/subjects');
  },

  /** 创建模拟测验（组卷，耗时较长） */
  async createQuiz(config: QuizConfigRequest): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/quiz/sessions', config, {
      timeout: 180000,
    });
  },

  async getSession(sessionId: string): Promise<InterviewSession> {
    return request.get<InterviewSession>(`/api/quiz/sessions/${sessionId}`);
  },

  /** 整卷交卷 */
  async submit(sessionId: string, answers: BatchSubmitAnswerItem[]): Promise<BatchSubmitAnswerResponse> {
    return request.post<BatchSubmitAnswerResponse>(`/api/quiz/sessions/${sessionId}/submit`, { answers }, {
      timeout: 120000,
    });
  },

  async getDetail(sessionId: string): Promise<InterviewDetail> {
    return request.get<InterviewDetail>(`/api/quiz/sessions/${sessionId}/details`);
  },

  async exportPdf(sessionId: string): Promise<Blob> {
    return request.download(`/api/quiz/sessions/${sessionId}/export`);
  },

  async history(): Promise<QuizSessionMeta[]> {
    return request.get<QuizSessionMeta[]>('/api/quiz/history');
  },

  /** 再测一次：沿用上次组卷参数重新出题 */
  async retry(sessionId: string): Promise<InterviewSession> {
    return request.post<InterviewSession>(`/api/quiz/sessions/${sessionId}/retry`, undefined, {
      timeout: 180000,
    });
  },

  async remove(sessionId: string): Promise<void> {
    return request.delete<void>(`/api/quiz/sessions/${sessionId}`);
  },

  /** 错题本 */
  async wrongQuestions(skillId?: string, limit?: number): Promise<WrongQuestionItem[]> {
    const params = new URLSearchParams();
    if (skillId) params.set('skillId', skillId);
    if (limit) params.set('limit', String(limit));
    const query = params.toString();
    return request.get<WrongQuestionItem[]>(`/api/quiz/wrong-questions${query ? `?${query}` : ''}`);
  },

  /** 错题重练 */
  async retryWrongQuestions(items: WrongQuestionRef[]): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/quiz/wrong-questions/retry', { items }, {
      timeout: 60000,
    });
  },
};

export default quizApi;
