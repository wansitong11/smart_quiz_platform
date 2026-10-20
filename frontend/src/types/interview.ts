// 面试相关类型定义

import type { CategoryDTO } from '../api/skill';

export interface InterviewSession {
  sessionId: string;
  resumeText: string;
  totalQuestions: number;
  currentQuestionIndex: number;
  questions: InterviewQuestion[];
  status: 'CREATED' | 'IN_PROGRESS' | 'COMPLETED' | 'EVALUATED';
  knowledgeBaseId?: number | null;
  subjectCategory?: string | null;
  /** 试卷层级树：卷 → 大题 → 小节 → 材料/题目 */
  paperTree?: PaperTree | null;
  /** 来源试卷 ID：图形选项取资源用 */
  paperId?: number | null;
}

/** 试卷层级树节点 */
export interface PaperNode {
  id: string;
  /** PAPER | SECTION | MATERIAL | QUESTION | INSTRUCTION */
  type: string;
  order: number;
  depth: number;
  /** 是否结构终点（不再往下分） */
  leaf: boolean;
  title?: string | null;
  subtitle?: string | null;
  /** MATERIAL / INSTRUCTION 的正文 */
  content?: string | null;
  meta?: {
    fullScore?: number | null;
    questionCount?: number | null;
    durationMinutes?: number | null;
    /** 题干图 / 材料卡片 / 表格资源 */
    assetIds?: string[] | null;
  } | null;
  questionIndex?: number | null;
  question?: InterviewQuestion | null;
  children?: PaperNode[] | null;
}

export interface PaperTree {
  version: number;
  root: PaperNode;
}

export interface InterviewQuestion {
  questionIndex: number;
  question: string;
  type: string;
  category: string;
  /** 题型：SINGLE_CHOICE / MULTI_CHOICE / FILL_BLANK / SHORT_ANSWER / READING / WRITING / OTHER */
  questionType?: string | null;
  /** 选择题选项（无选项时为空） */
  options?: QuestionOption[] | null;
  /** 题目来源页（扫描件识别时写入）：用于"查看原页" */
  sourcePageFrom?: number | null;
  sourcePageTo?: number | null;
  topicSummary?: string | null;
  userAnswer: string | null;
  score: number | null;
  feedback: string | null;
  isFollowUp?: boolean;
  parentQuestionIndex?: number | null;
  referenceAnswer?: string | null;
  keyPoints?: string[];
  scoringRubric?: string | null;
  sourceContext?: string | null;
  /** 该题分值（试卷 / 练习材料场景；自动出题场景为空） */
  fullScore?: number | null;
}

export interface CreateInterviewRequest {
  resumeText: string;
  questionCount: number;
  resumeId?: number;
  forceCreate?: boolean;
  llmProvider?: string;
  skillId: string;
  difficulty?: string;
  customCategories?: CategoryDTO[];
  jdText?: string;
  requestId?: string;
}

export interface SubmitAnswerRequest {
  sessionId: string;
  questionIndex: number;
  answer: string;
}

export interface SubmitAnswerResponse {
  hasNextQuestion: boolean;
  nextQuestion: InterviewQuestion | null;
  currentIndex: number;
  totalQuestions: number;
}

export interface CurrentQuestionResponse {
  completed: boolean;
  question?: InterviewQuestion;
  message?: string;
}

export interface InterviewReport {
  sessionId: string;
  totalQuestions: number;
  overallScore: number;
  /** 卷面总分（试卷场景才有值，其它场景为 0） */
  totalFullScore?: number;
  /** 卷面得分（试卷场景才有值） */
  paperScore?: number;
  categoryScores: CategoryScore[];
  questionDetails: QuestionEvaluation[];
  overallFeedback: string;
  strengths: string[];
  improvements: string[];
  referenceAnswers: ReferenceAnswer[];
}

export interface CategoryScore {
  category: string;
  score: number;
  questionCount: number;
}

export interface QuestionEvaluation {
  questionIndex: number;
  question: string;
  category: string;
  userAnswer: string;
  score: number;
  feedback: string;
  fullScore?: number | null;
  questionScore?: number | null;
}

export interface ReferenceAnswer {
  questionIndex: number;
  question: string;
  referenceAnswer: string;
  keyPoints: string[];
}
/**
 * 整卷交卷（试卷 / 练习材料场景）
 */
export interface BatchSubmitAnswerItem {
  questionIndex: number;
  /** 题型（回传后端用于选择写规范化与确定性判分） */
  questionType?: string | null;
  /** 选择题选中的选项 key */
  selectedKeys?: string[];
  answer: string;
}

/** 选择题选项 */
export interface QuestionOption {
  key: string;
  text: string;
  /** 图形选项：资源 key（通过 /api/papers/{id}/assets/{key} 获取） */
  assetId?: string | null;
}

export interface BatchSubmitAnswerResponse {
  sessionId: string;
  answeredCount: number;
  totalQuestions: number;
  blankCount: number;
  status: string;
  evaluateStatus: string;
}
