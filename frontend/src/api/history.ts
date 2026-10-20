import { request } from './request';
import { scopeQuery, type DataScopeParam } from './scope';
import type { InterviewSession, PaperTree } from '../types/interview';
import type { RecognitionStatus } from '../types/paperRecognition';

export type AnalyzeStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';
export type EvaluateStatus = 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'FAILED';

export interface ResumeListItem {
  id: number;
  filename: string;
  fileSize: number;
  uploadedAt: string;
  accessCount: number;
  latestScore?: number;
  lastAnalyzedAt?: string;
  interviewCount: number;
  analysisStatus?: AnalyzeStatus;
  analysisError?: string;
  recognitionStatus?: RecognitionStatus;
  storageUrl?: string;
}

export interface ResumeStats {
  totalCount: number;
  totalInterviewCount: number;
  totalAccessCount: number;
}

/**
 * 试卷 / 练习材料解析摘要（结构化切题结果概览）
 */
export interface AnalysisItem {
  id: number;
  questionCount: number;
  totalScore: number;
  summary: string;
  analyzedAt: string;
  practiceSessionId?: string | null;
  topics: string[];
  warnings: string[];
}

export interface InterviewItem {
  id: number;
  sessionId: string;
  totalQuestions: number;
  status: string;
  evaluateStatus?: EvaluateStatus;
  evaluateError?: string;
  overallScore: number | null;
  /** 会话来源：NORMAL / KNOWLEDGE_BASE / PAPER */
  sourceType?: string | null;
  /** 来源试卷 ID（PAPER 会话） */
  resumeId?: number | null;
  overallFeedback: string | null;
  createdAt: string;
  completedAt: string | null;
  questions?: unknown[];
  strengths?: string[];
  improvements?: string[];
  referenceAnswers?: unknown[];
}

export interface AnswerItem {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
  referenceAnswer?: string;
  keyPoints?: string[];
  answeredAt: string;
}

export interface ResumeDetail {
  id: number;
  filename: string;
  fileSize: number;
  contentType: string;
  storageUrl: string;
  uploadedAt: string;
  accessCount: number;
  resumeText: string;
  analysisStatus?: AnalyzeStatus;
  analysisError?: string;
  /** 扫描件识别状态：NONE/READY 表示走普通文字链路 */
  recognitionStatus?: RecognitionStatus;
  recognitionError?: string | null;
  pageCount?: number | null;
  analyses: AnalysisItem[];
  interviews: InterviewItem[];
}

export interface InterviewDetail extends InterviewItem {
  evaluateStatus?: EvaluateStatus;
  evaluateError?: string;
  answers: AnswerItem[];
  /** 试卷层级树（老数据可能为空） */
  paperTree?: PaperTree | null;
  /** 按小节汇总的得分 */
  sectionScores?: SectionScore[];
}

export interface SectionScore {
  title: string;
  subtitle?: string | null;
  level: number;
  questionCount: number;
  fullScore: number;
  score: number;
  scoreRate: number;
}

export const historyApi = {
  /**
   * 获取所有简历列表
   * @param scope 管理员传 'all' 时查看全部用户的试卷
   */
  async getResumes(scope?: DataScopeParam): Promise<ResumeListItem[]> {
    const query = scopeQuery(scope);
    return request.get<ResumeListItem[]>(`/api/resumes${query ? `?${query}` : ''}`);
  },

  /**
   * 获取简历详情
   */
  async getResumeDetail(id: number, signal?: AbortSignal): Promise<ResumeDetail> {
    return request.get<ResumeDetail>(`/api/resumes/${id}/detail`, { signal });
  },

  /**
   * 获取面试详情
   */
  async getInterviewDetail(sessionId: string): Promise<InterviewDetail> {
    return request.get<InterviewDetail>(`/api/interview/sessions/${sessionId}/details`);
  },

  /**
   * 导出简历分析报告PDF
   */
  async exportAnalysisPdf(resumeId: number): Promise<Blob> {
    return request.download(`/api/resumes/${resumeId}/export`);
  },

  /**
   * 导出面试报告PDF
   */
  async exportInterviewPdf(sessionId: string): Promise<Blob> {
    return request.download(`/api/interview/sessions/${sessionId}/export`);
  },

  /**
   * 删除简历
   */
  async deleteResume(id: number): Promise<void> {
    return request.delete(`/api/resumes/${id}`);
  },

  /**
   * 删除面试记录
   */
  async deleteInterview(sessionId: string): Promise<void> {
    return request.delete(`/api/interview/sessions/${sessionId}`);
  },

  /**
   * 获取简历统计信息
   */
  async getStatistics(): Promise<ResumeStats> {
    return request.get<ResumeStats>('/api/resumes/statistics');
  },

  /**
   * 重新分析简历
   */
  async reanalyze(id: number): Promise<void> {
    return request.post(`/api/resumes/${id}/reanalyze`);
  },

  /**
   * 开始（或继续）一次试卷作答。
   * 上一次没交卷就返回那一次，已交卷则基于同一份切题结果新开一次。
   */
  async startPaperAttempt(paperId: number): Promise<InterviewSession> {
    return request.post<InterviewSession>(`/api/resumes/${paperId}/attempts`);
  },
};
