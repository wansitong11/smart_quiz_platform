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

/** 考点（目录里每个科目下的平铺考点） */
export interface QuizKnowledgePoint {
  code: string;
  label: string;
}

/** 题型与默认分、权重 */
export interface QuizQuestionType {
  type: string;
  defaultScore: number;
  weight: number;
}

/** 考试分组展示信息 */
export interface QuizExamDisplay {
  icon?: string | null;
  gradient?: string | null;
  iconBg?: string | null;
  iconColor?: string | null;
}

/** 目录里的科目（系统预设或我的自定义） */
export interface QuizCatalogSubject {
  code: string;
  name: string;
  description?: string | null;
  examCode: string;
  custom: boolean;
  knowledgePoints: QuizKnowledgePoint[];
  questionTypes: QuizQuestionType[];
  suggestedMinutes?: number | null;
  clonedFromCode?: string | null;
  outlineText?: string | null;
}

/** 考试分组 */
export interface QuizExam {
  code: string;
  name: string;
  description?: string | null;
  display?: QuizExamDisplay | null;
  customBucket: boolean;
  sortOrder: number;
  subjects: QuizCatalogSubject[];
}

/** 测验目录（考试 → 科目 → 考点 → 题型） */
export interface QuizCatalog {
  exams: QuizExam[];
}

/** 新建/编辑科目时的考点输入 */
export interface QuizKnowledgePointInput {
  label: string;
}

/** 新建/编辑科目时的题型输入 */
export interface QuizQuestionTypeInput {
  type: string;
  defaultScore: number;
  weight: number;
}

/** 新建/编辑自定义科目入参 */
export interface QuizSubjectRequest {
  name: string;
  description?: string;
  examCode: string;
  knowledgePoints: QuizKnowledgePointInput[];
  questionTypes: QuizQuestionTypeInput[];
  suggestedMinutes?: number;
  outlineText?: string;
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
export type WrongQuestionStatus = 'PENDING' | 'MASTERED' | 'ARCHIVED';

export interface WrongQuestionMaterial {
  title: string | null;
  content: string;
  assetIds: string[];
}

export interface WrongQuestionItem {
  questionKey: string;
  skillId: string;
  examCode: string | null;
  examName: string | null;
  subjectName: string | null;
  knowledgePointKey: string | null;
  knowledgePointName: string | null;
  questionType: string | null;
  typeLabel: string;
  stem: string;
  options: Array<{ key: string; text: string; assetId?: string | null }>;
  fullScore: number | null;
  materials: WrongQuestionMaterial[];
  paperId: number | null;
  assetIds: string[];
  userAnswer: string | null;
  referenceAnswer: string | null;
  analysis: string | null;
  lastScoreRate: number | null;
  correctCount: number;
  wrongCount: number;
  status: WrongQuestionStatus;
  statusLabel: string;
  lastWrongAt: string | null;
  lastCorrectAt: string | null;
  masteredAt: string | null;
  archivedAt: string | null;
}

export interface WrongQuestionCountItem {
  key: string;
  label: string;
  count: number;
}

export interface WrongQuestionPage {
  items: WrongQuestionItem[];
  total: number;
  page: number;
  size: number;
  statusCounts: WrongQuestionCountItem[];
  subjectCounts: WrongQuestionCountItem[];
}

export interface WrongQuestionFilters {
  examCode?: string;
  subjectCode?: string;
  knowledgePointKey?: string;
  questionType?: string;
  status?: WrongQuestionStatus;
  keyword?: string;
}

export interface WrongQuestionRetryRequest {
  mode: 'SELECTED' | 'RANDOM';
  questionKeys?: string[];
  scope?: WrongQuestionFilters;
  count?: number;
}

export const quizApi = {
  /** 学科与知识板块 */
  async listSubjects(): Promise<QuizSubject[]> {
    return request.get<QuizSubject[]>('/api/quiz/subjects');
  },

  /** 考试 → 科目 → 考点 → 题型目录（系统科目 + 我的科目） */
  async catalog(): Promise<QuizCatalog> {
    return request.get<QuizCatalog>('/api/quiz/catalog');
  },

  /** 新建自定义科目 */
  async createSubject(payload: QuizSubjectRequest): Promise<QuizCatalogSubject> {
    return request.post<QuizCatalogSubject>('/api/quiz/subjects', payload);
  },

  /** 编辑自定义科目 */
  async updateSubject(subjectCode: string, payload: QuizSubjectRequest): Promise<QuizCatalogSubject> {
    return request.put<QuizCatalogSubject>(`/api/quiz/subjects/${subjectCode}`, payload);
  },

  /** 软删除自定义科目 */
  async deleteSubject(subjectCode: string): Promise<void> {
    return request.delete<void>(`/api/quiz/subjects/${subjectCode}`);
  },

  /** 复制内置科目为我的科目 */
  async cloneSubject(sourceCode: string): Promise<QuizCatalogSubject> {
    return request.post<QuizCatalogSubject>('/api/quiz/subjects/clone', { sourceCode });
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
  async wrongQuestions(
    filters: WrongQuestionFilters = {},
    page = 0,
    size = 20,
  ): Promise<WrongQuestionPage> {
    const params = new URLSearchParams();
    Object.entries(filters).forEach(([key, value]) => {
      if (value != null && value !== '') params.set(key, String(value));
    });
    params.set('page', String(page));
    params.set('size', String(size));
    const query = params.toString();
    return request.get<WrongQuestionPage>(`/api/quiz/wrong-questions${query ? `?${query}` : ''}`);
  },

  /** 错题重练 */
  async retryWrongQuestions(payload: WrongQuestionRetryRequest): Promise<InterviewSession> {
    return request.post<InterviewSession>('/api/quiz/wrong-questions/retry', payload, {
      timeout: 60000,
    });
  },

  /** 移出 / 恢复错题 */
  async updateWrongQuestionStatus(
    questionKey: string,
    status: Exclude<WrongQuestionStatus, 'MASTERED'>,
  ): Promise<void> {
    return request.put<void>(
      `/api/quiz/wrong-questions/${encodeURIComponent(questionKey)}/status`,
      { status },
    );
  },
};

export default quizApi;
