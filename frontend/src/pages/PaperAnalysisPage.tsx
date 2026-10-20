import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MutableRefObject, ReactNode } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import {
  AlertCircle,
  CheckCircle2,
  ClipboardList,
  FileText,
  Loader2,
  RotateCcw,
  Send,
} from 'lucide-react';
import { resumeApi } from '../api/resume';
import { historyApi, type InterviewDetail, type ResumeDetail } from '../api/history';
import { interviewApi } from '../api/interview';
import QuestionAnswerInput, {
  EMPTY_ANSWER,
  isAnswerEmpty,
  toSubmitAnswer,
  type AnswerValue,
} from '../components/QuestionAnswerInput';
import PaperTreeView from '../components/PaperTreeView';
import PaperRecognitionPanel from '../components/paper-recognition/PaperRecognitionPanel';
import { buildQuestionGroups, groupProgress, sectionTitlesOf } from '../utils/paperTree';
import type { PaperTree } from '../types/interview';
import { summarizeCategories, summarizePaperScore } from '../utils/paperScore';
import type { InterviewQuestion } from '../types/interview';
import UploadPage from './UploadPage';
import { ROUTES } from '../constants/routes';

type Stage = 'upload' | 'recognition' | 'extracting' | 'ready' | 'practice' | 'grading' | 'report';

const ANSWERS_STORAGE_PREFIX = 'smart-quiz-paper-answers:';
const POLL_INTERVAL_MS = 2500;

interface ReportQuestionLike {
  questionIndex: number;
  category?: string | null;
  fullScore?: number | null;
}

/**
 * 试卷分析页：上传做过的试卷 / 练习材料 → 结构化切题 → 整卷作答 → 一次交卷 → 批改报告。
 *
 * 支持用 URL 参数恢复现场：
 * - `?paper=123` 试卷已上传，展示切题进度与解析摘要
 * - `?session=xxx` 练习已生成，恢复作答或查看报告
 */
export default function PaperAnalysisPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [stage, setStage] = useState<Stage>('upload');
  const [paperDetail, setPaperDetail] = useState<ResumeDetail | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sessionPaperId, setSessionPaperId] = useState<number | null>(null);
  const [questions, setQuestions] = useState<InterviewQuestion[]>([]);
  const [paperTree, setPaperTree] = useState<PaperTree | null>(null);
  const [answers, setAnswers] = useState<Record<number, AnswerValue>>({});
  const [report, setReport] = useState<InterviewDetail | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const questionRefs = useRef<Record<number, HTMLDivElement | null>>({});

  const paperId = useMemo(() => {
    const value = searchParams.get('paper');
    return value ? Number(value) : null;
  }, [searchParams]);
  const sessionIdParam = searchParams.get('session');

  // ==================== 数据加载 ====================

  const loadQuestions = useCallback(async (id: string) => {
    const session = await interviewApi.getSession(id);
    setSessionId(id);
    setSessionPaperId(session.paperId ?? null);
    setQuestions(session.questions ?? []);
    setPaperTree(session.paperTree ?? null);
    const cached = localStorage.getItem(ANSWERS_STORAGE_PREFIX + id);
    if (cached) {
      try {
        const parsed = JSON.parse(cached) as Record<number, AnswerValue | string>;
        const migrated: Record<number, AnswerValue> = {};
        Object.entries(parsed).forEach(([key, item]) => {
          // 兼容早期版本把答案存成纯字符串的格式
          migrated[Number(key)] = typeof item === 'string'
            ? { selectedKeys: [], text: item }
            : { selectedKeys: item?.selectedKeys ?? [], text: item?.text ?? '' };
        });
        setAnswers(migrated);
        return;
      } catch {
        localStorage.removeItem(ANSWERS_STORAGE_PREFIX + id);
      }
    }
    const restored: Record<number, AnswerValue> = {};
    (session.questions ?? []).forEach(question => {
      if (question.userAnswer) {
        restored[question.questionIndex] = { selectedKeys: [], text: question.userAnswer };
      }
    });
    setAnswers(restored);
  }, []);

  const loadReport = useCallback(async (id: string) => {
    const detail = await historyApi.getInterviewDetail(id);
    setReport(detail);
    if (detail.evaluateStatus === 'COMPLETED') {
      setStage('report');
    } else if (detail.evaluateStatus === 'FAILED') {
      setStage('report');
      setError(detail.evaluateError || '批改失败，请稍后重试');
    } else {
      setStage('grading');
    }
  }, []);

  const loadPaper = useCallback(async (id: number) => {
    const detail = await historyApi.getResumeDetail(id);
    setPaperDetail(detail);
    const latest = detail.analyses?.[0];
    const recognition = detail.recognitionStatus;
    // 扫描件：识别 / 校对阶段优先；确认切题后 recognitionStatus=EXTRACTING，落回切题进度
    if (recognition && recognition !== 'NONE' && recognition !== 'READY'
      && recognition !== 'EXTRACTING') {
      setStage('recognition');
      return;
    }
    if (detail.analysisStatus === 'COMPLETED' && latest?.practiceSessionId) {
      setStage('ready');
    } else if (detail.analysisStatus === 'FAILED') {
      setStage('ready');
    } else {
      setStage('extracting');
    }
  }, []);

  // 首次进入时按 URL 恢复现场
  useEffect(() => {
    let cancelled = false;
    const restore = async () => {
      try {
        if (sessionIdParam) {
          const detail = await historyApi.getInterviewDetail(sessionIdParam);
          if (cancelled) return;
          if (detail.status === 'COMPLETED' || detail.status === 'EVALUATED') {
            setSessionId(sessionIdParam);
            setReport(detail);
            if (detail.evaluateStatus && detail.evaluateStatus !== 'COMPLETED'
              && detail.evaluateStatus !== 'FAILED') {
              setStage('grading');
            } else {
              setStage('report');
            }
          } else {
            await loadQuestions(sessionIdParam);
            if (!cancelled) setStage('practice');
          }
        } else if (paperId) {
          await loadPaper(paperId);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '加载失败');
        }
      }
    };
    restore();
    return () => {
      cancelled = true;
    };
  }, [paperId, sessionIdParam, loadPaper, loadQuestions]);

  // 切题进度轮询
  useEffect(() => {
    if (stage !== 'extracting' || !paperId) return;
    const timer = window.setInterval(() => {
      loadPaper(paperId).catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [stage, paperId, loadPaper]);

  // 批改进度轮询
  useEffect(() => {
    if (stage !== 'grading' || !sessionId) return;
    const timer = window.setInterval(() => {
      loadReport(sessionId).catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [stage, sessionId, loadReport]);

  // 作答本地暂存
  useEffect(() => {
    if (!sessionId || stage !== 'practice') return;
    localStorage.setItem(ANSWERS_STORAGE_PREFIX + sessionId, JSON.stringify(answers));
  }, [answers, sessionId, stage]);

  // ==================== 交互 ====================

  const handleReanalyze = async () => {
    if (!paperId) return;
    setError('');
    setStage('extracting');
    try {
      await resumeApi.reanalyze(paperId);
      await loadPaper(paperId);
    } catch (err) {
      setError(err instanceof Error ? err.message : '重新切题失败');
    }
  };

  /**
   * 开始作答 / 再练一遍：后端会复用没交卷的那一次，已经交卷则基于同一份切题结果新开一次，
   * 交卷后这条练习会作为一条新的作答记录出现在面试记录里。
   * 从试卷进入按试卷 ID 开；从报告（?session=）进入按会话对应的试卷开。
   */
  const handleStartPractice = async () => {
    if (!paperId && !sessionId) {
      setError('该材料还没有生成练习，请先等待切题完成');
      return;
    }
    setError('');
    try {
      const session = paperId
        ? await historyApi.startPaperAttempt(paperId)
        : await interviewApi.startNewAttempt(sessionId as string);
      await loadQuestions(session.sessionId);
      setSearchParams({ session: session.sessionId });
      setStage('practice');
    } catch (err) {
      setError(err instanceof Error ? err.message : '打不开练习');
    }
  };

  const handleSubmit = async () => {
    if (!sessionId) return;
    const blank = questions.filter(q => isAnswerEmpty(answers[q.questionIndex])).length;
    const confirmed = window.confirm(
      blank > 0
        ? `还有 ${blank} 道题没有作答，交卷后这些题按 0 分计。确定交卷吗？`
        : '确定交卷吗？交卷后将立即开始批改。'
    );
    if (!confirmed) return;

    setSubmitting(true);
    setError('');
    try {
      await interviewApi.submitWholePaper(
        sessionId,
        questions.map(question => {
          const submit = toSubmitAnswer(answers[question.questionIndex]);
          return {
            questionIndex: question.questionIndex,
            questionType: question.questionType ?? null,
            selectedKeys: submit.selectedKeys,
            answer: submit.answer,
          };
        })
      );
      localStorage.removeItem(ANSWERS_STORAGE_PREFIX + sessionId);
      setStage('grading');
      await loadReport(sessionId);
    } catch (err) {
      setError(err instanceof Error ? err.message : '交卷失败');
    } finally {
      setSubmitting(false);
    }
  };

  const scrollToQuestion = (questionIndex: number) => {
    questionRefs.current[questionIndex]?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  // ==================== 报告数据 ====================

  const reportQuestions = useMemo<ReportQuestionLike[]>(() => {
    if (!report) return [];
    const raw = (report.questions ?? []) as ReportQuestionLike[];
    if (raw.length > 0) return raw;
    return (report.answers ?? []).map(answer => ({
      questionIndex: answer.questionIndex,
      category: answer.category,
      fullScore: null,
    }));
  }, [report]);

  const scoreSummary = useMemo(
    () => summarizePaperScore(reportQuestions, report?.answers ?? []),
    [reportQuestions, report]
  );
  const categoryStats = useMemo(
    () => summarizeCategories(reportQuestions, report?.answers ?? []),
    [reportQuestions, report]
  );
  const weakCategories = categoryStats.filter(stat => stat.questionCount > 0).slice(0, 3);
  const answeredCount = questions.filter(q => !isAnswerEmpty(answers[q.questionIndex])).length;
  const totalFullScore = questions.reduce((sum, q) => sum + (q.fullScore ?? 0), 0);

  // ==================== 渲染 ====================

  return (
    <div className="max-w-5xl mx-auto px-4 pb-16">
      <div className="flex items-center gap-3 py-6">
        <div className="w-10 h-10 rounded-xl bg-primary-500/10 flex items-center justify-center">
          <ClipboardList className="w-5 h-5 text-primary-500" />
        </div>
        <div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-white">试卷分析</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400">
            上传做过的试卷或练习材料，AI 切题后整卷作答，自动批改并给出学情反馈
          </p>
        </div>
      </div>

      {error && (
        <div className="mb-6 flex items-start gap-3 p-4 rounded-xl bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800">
          <AlertCircle className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" />
          <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
        </div>
      )}

      {stage === 'upload' && (
        /* 上传界面与「试卷管理 → 上传试卷」共用同一个组件（批量上传 + 逐个异步切题），
           切完可以直接点「开始作答」进入下面的作答流程 */
        <UploadPage
          embedded
          onBack={() => navigate(ROUTES.resumeHistory)}
          onOpenPaper={id => setSearchParams({ paper: String(id) })}
        />
      )}

      {stage === 'extracting' && (
        <StageCard
          icon={<Loader2 className="w-8 h-8 text-primary-500 animate-spin" />}
          title="AI 正在切题"
          description="正在把整份材料拆成一道一道题目，并补全知识点、参考答案与评分标准"
        />
      )}

      {stage === 'recognition' && paperId && (
        <PaperRecognitionPanel
          paperId={paperId}
          onConfirmed={() => {
            setStage('extracting');
            void loadPaper(paperId);
          }}
        />
      )}

      {stage === 'ready' && (
        <ReadyPanel
          detail={paperDetail}
          onStart={handleStartPractice}
          onReanalyze={handleReanalyze}
        />
      )}

      {stage === 'practice' && (
        <PracticePanel
          questions={questions}
          paperTree={paperTree}
          answers={answers}
          answeredCount={answeredCount}
          totalFullScore={totalFullScore}
          paperId={paperId ?? sessionPaperId ?? undefined}
          submitting={submitting}
          questionRefs={questionRefs}
          onAnswerChange={(index, value) =>
            setAnswers(prev => ({ ...prev, [index]: value }))
          }
          onScrollTo={scrollToQuestion}
          onSubmit={handleSubmit}
        />
      )}

      {stage === 'grading' && (
        <StageCard
          icon={<Loader2 className="w-8 h-8 text-primary-500 animate-spin" />}
          title="AI 正在批改"
          description="正在逐题判分并汇总知识板块表现，通常需要十几秒"
        />
      )}

      {stage === 'report' && report && (
        <ReportPanel
          report={report}
          summary={scoreSummary}
          weakCategories={weakCategories}
          onRetry={handleStartPractice}
        />
      )}
    </div>
  );
}

// ==================== 子组件 ====================

function StageCard({ icon, title, description }: {
  icon: ReactNode;
  title: string;
  description: string;
}) {
  return (
    <div className="bg-white dark:bg-slate-800 rounded-2xl p-12 text-center">
      <div className="w-16 h-16 mx-auto mb-6 bg-primary-500/10 rounded-full flex items-center justify-center">
        {icon}
      </div>
      <h3 className="text-lg font-semibold text-slate-800 dark:text-slate-200 mb-2">{title}</h3>
      <p className="text-sm text-slate-500 dark:text-slate-400">{description}</p>
    </div>
  );
}

function ReadyPanel({ detail, onStart, onReanalyze }: {
  detail: ResumeDetail | null;
  onStart: () => void;
  onReanalyze: () => void;
}) {
  const latest = detail?.analyses?.[0];
  const canStart = Boolean(latest?.practiceSessionId);

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
        <div className="flex items-start justify-between gap-4 flex-wrap mb-6">
          <div className="flex items-center gap-3">
            <FileText className="w-5 h-5 text-primary-500" />
            <div>
              <p className="font-semibold text-slate-800 dark:text-slate-200">
                {detail?.filename || '试卷 / 练习材料'}
              </p>
              <p className="text-sm text-slate-500 dark:text-slate-400">
                已识别 {latest?.questionCount ?? 0} 道题，卷面总分 {latest?.totalScore ?? 0} 分
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={onReanalyze}
              className="px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-lg text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
            >
              重新切题
            </button>
            <button
              onClick={() => onStart()}
              disabled={!canStart}
              className="px-5 py-2 bg-primary-500 text-white rounded-lg text-sm font-medium hover:bg-primary-600 transition-colors disabled:opacity-50"
            >
              开始作答
            </button>
          </div>
        </div>

        {latest?.summary && (
          <p className="text-slate-700 dark:text-slate-300 mb-6">{latest.summary}</p>
        )}

        {latest?.topics && latest.topics.length > 0 && (
          <div className="flex flex-wrap gap-2 mb-4">
            {latest.topics.map((topic, index) => {
              const [label, count, score] = topic.split('|');
              return (
                <span
                  key={index}
                  className="px-3 py-1.5 bg-indigo-50 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 border border-indigo-200 dark:border-indigo-800 rounded-lg text-sm"
                >
                  {label}
                  <span className="ml-2 text-xs text-indigo-500 dark:text-indigo-400">
                    {count}
                    {score ? ` · ${score}` : ''}
                  </span>
                </span>
              );
            })}
          </div>
        )}

        {latest?.warnings && latest.warnings.length > 0 && (
          <div className="mt-4 p-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-xl">
            <p className="text-sm font-medium text-amber-700 dark:text-amber-400 mb-2">
              解析告警（{latest.warnings.length}）
            </p>
            <ul className="space-y-1">
              {latest.warnings.slice(0, 6).map((warning, index) => (
                <li key={index} className="text-sm text-amber-700/90 dark:text-amber-300/90">
                  · {warning}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </div>
  );
}

function PracticePanel({
  questions,
  paperTree,
  answers,
  answeredCount,
  totalFullScore,
  paperId,
  submitting,
  questionRefs,
  onAnswerChange,
  onScrollTo,
  onSubmit,
}: {
  questions: InterviewQuestion[];
  paperTree: PaperTree | null;
  answers: Record<number, AnswerValue>;
  answeredCount: number;
  totalFullScore: number;
  paperId?: number;
  submitting: boolean;
  questionRefs: MutableRefObject<Record<number, HTMLDivElement | null>>;
  onAnswerChange: (index: number, value: AnswerValue) => void;
  onScrollTo: (index: number) => void;
  onSubmit: () => void;
}) {
  const navGroups = buildQuestionGroups(paperTree);
  const isAnswered = (questionIndex: number) => !isAnswerEmpty(answers[questionIndex]);

  return (
    <div className="space-y-6">
      <div className="sticky top-4 z-10 bg-white/95 dark:bg-slate-800/95 backdrop-blur rounded-2xl p-4 border border-slate-200 dark:border-slate-700">
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div className="text-sm text-slate-600 dark:text-slate-300">
            共 <strong className="text-slate-900 dark:text-white">{questions.length}</strong> 题
            {totalFullScore > 0 && <> · 卷面总分 <strong className="text-slate-900 dark:text-white">{totalFullScore}</strong> 分</>}
            {' '}· 已作答 <strong className="text-primary-600 dark:text-primary-400">{answeredCount}</strong> 题
          </div>
          <button
            onClick={onSubmit}
            disabled={submitting || questions.length === 0}
            className="px-5 py-2 bg-primary-500 text-white rounded-lg text-sm font-medium hover:bg-primary-600 transition-colors disabled:opacity-50 flex items-center gap-2"
          >
            {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
            {submitting ? '交卷中...' : '交卷并批改'}
          </button>
        </div>
        {navGroups.length > 0 ? (
          <div className="mt-3 flex flex-wrap gap-2">
            {navGroups.map(group => {
              const progress = groupProgress(group, isAnswered);
              return (
                <div
                  key={group.id}
                  className="flex items-center gap-1.5 rounded-xl border border-dashed border-slate-200 dark:border-slate-600 px-2 py-1"
                >
                  <span className="text-xs text-slate-500 dark:text-slate-400">
                    {group.title}
                    <span className="ml-1 text-slate-400">
                      {progress.answered}/{progress.total}
                    </span>
                  </span>
                  {group.questionIndexes.map(questionIndex => (
                    <button
                      key={questionIndex}
                      onClick={() => onScrollTo(questionIndex)}
                      className={`w-7 h-7 rounded-lg text-xs font-medium border transition-colors ${
                        isAnswered(questionIndex)
                          ? 'bg-primary-500 text-white border-primary-500'
                          : 'bg-white dark:bg-slate-700 text-slate-500 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-primary-400'
                      }`}
                    >
                      {questionIndex + 1}
                    </button>
                  ))}
                </div>
              );
            })}
          </div>
        ) : (
          <div className="mt-3 flex flex-wrap gap-1.5">
            {questions.map(question => (
              <button
                key={question.questionIndex}
                onClick={() => onScrollTo(question.questionIndex)}
                className={`w-8 h-8 rounded-lg text-xs font-medium border transition-colors ${
                  isAnswered(question.questionIndex)
                    ? 'bg-primary-500 text-white border-primary-500'
                    : 'bg-white dark:bg-slate-700 text-slate-500 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-primary-400'
                }`}
              >
                {question.questionIndex + 1}
              </button>
            ))}
          </div>
        )}
      </div>

      {paperTree ? (
        <PaperTreeView
          tree={paperTree}
          answers={answers}
          paperId={paperId}
          onAnswerChange={onAnswerChange}
        />
      ) : (
        questions.map(question => (
          <motion.div
            key={question.questionIndex}
            ref={element => {
              questionRefs.current[question.questionIndex] = element;
            }}
            className="bg-white dark:bg-slate-800 rounded-2xl p-6"
            initial={{ opacity: 0, y: 12 }}
            animate={{ opacity: 1, y: 0 }}
          >
            <div className="flex items-center gap-2 flex-wrap mb-3">
              <span className="px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded text-xs font-medium">
                第 {question.questionIndex + 1} 题
              </span>
              {question.category && (
                <span className="px-2 py-0.5 bg-indigo-50 dark:bg-indigo-900/40 text-indigo-600 dark:text-indigo-300 rounded text-xs">
                  {question.category}
                </span>
              )}
              {question.fullScore ? (
                <span className="px-2 py-0.5 bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400 rounded text-xs">
                  {question.fullScore} 分
                </span>
              ) : null}
            </div>
            <p className="text-slate-800 dark:text-slate-100 whitespace-pre-wrap leading-relaxed mb-4">
              {question.question}
            </p>
            <QuestionAnswerInput
              question={question}
              value={answers[question.questionIndex] ?? EMPTY_ANSWER}
              paperId={paperId}
              onChange={value => onAnswerChange(question.questionIndex, value)}
            />
          </motion.div>
        ))
      )}

      <div className="flex justify-end">
        <button
          onClick={onSubmit}
          disabled={submitting || questions.length === 0}
          className="px-6 py-3 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-colors disabled:opacity-50 flex items-center gap-2"
        >
          {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
          {submitting ? '交卷中...' : '交卷并批改'}
        </button>
      </div>
    </div>
  );
}

function ReportPanel({
  report,
  summary,
  weakCategories,
  onRetry,
}: {
  report: InterviewDetail;
  summary: ReturnType<typeof summarizePaperScore>;
  weakCategories: ReturnType<typeof summarizeCategories>;
  onRetry: () => void;
}) {
  const referenceMap = new Map<number, { referenceAnswer?: string | null; keyPoints?: string[] }>();
  report.answers?.forEach(answer => {
    if (answer.questionIndex != null) {
      referenceMap.set(answer.questionIndex, {
        referenceAnswer: answer.referenceAnswer,
        keyPoints: answer.keyPoints,
      });
    }
  });
  const optionsMap = new Map<number, NonNullable<InterviewQuestion['options']>>();
  ((report.questions ?? []) as Array<{ questionIndex?: number; options?: InterviewQuestion['options'] }>)
    .forEach(question => {
      if (question?.questionIndex != null && question.options && question.options.length >= 2) {
        optionsMap.set(question.questionIndex, question.options);
      }
    });

  return (
    <div className="space-y-6">
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-6">
          <div className="bg-gradient-to-br from-primary-50 dark:from-primary-900/30 to-white dark:to-slate-800 rounded-xl p-5">
            <span className="text-sm font-semibold text-primary-600 dark:text-primary-400 block mb-2">
              卷面得分
            </span>
            <span className="text-4xl font-bold text-slate-900 dark:text-white">
              {summary.totalFullScore > 0 ? summary.paperScore : report.overallScore ?? 0}
            </span>
            <span className="text-sm text-slate-500 dark:text-slate-400">
              {summary.totalFullScore > 0 ? ` / ${summary.totalFullScore} 分` : ' / 100 分'}
            </span>
          </div>
          <div className="bg-white dark:bg-slate-800 rounded-xl p-5 border border-slate-200 dark:border-slate-700">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 block mb-2">
              得分率
            </span>
            <span className="text-4xl font-bold text-slate-900 dark:text-white">
              {summary.scoreRate}
            </span>
            <span className="text-sm text-slate-500 dark:text-slate-400"> %</span>
          </div>
          <div className="bg-white dark:bg-slate-800 rounded-xl p-5 border border-slate-200 dark:border-slate-700">
            <span className="text-sm font-semibold text-slate-500 dark:text-slate-400 block mb-2">
              题目数量
            </span>
            <span className="text-4xl font-bold text-slate-900 dark:text-white">
              {report.answers?.length ?? 0}
            </span>
            <span className="text-sm text-slate-500 dark:text-slate-400"> 道</span>
          </div>
        </div>

        {report.overallFeedback && (
          <div className="p-4 bg-slate-50 dark:bg-slate-900/50 rounded-xl mb-4">
            <p className="text-slate-700 dark:text-slate-300 leading-relaxed whitespace-pre-wrap">
              {report.overallFeedback}
            </p>
          </div>
        )}

        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {report.strengths && report.strengths.length > 0 && (
            <div>
              <p className="text-sm font-semibold text-emerald-600 dark:text-emerald-400 mb-2">
                做得好的地方
              </p>
              <ul className="space-y-1">
                {report.strengths.map((item, index) => (
                  <li key={index} className="text-sm text-slate-600 dark:text-slate-300 flex gap-2">
                    <CheckCircle2 className="w-4 h-4 text-emerald-500 flex-shrink-0 mt-0.5" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {report.improvements && report.improvements.length > 0 && (
            <div>
              <p className="text-sm font-semibold text-amber-600 dark:text-amber-400 mb-2">
                需要改进
              </p>
              <ul className="space-y-1">
                {report.improvements.map((item, index) => (
                  <li key={index} className="text-sm text-slate-600 dark:text-slate-300 flex gap-2">
                    <AlertCircle className="w-4 h-4 text-amber-500 flex-shrink-0 mt-0.5" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </div>

      {weakCategories.length > 0 && (
        <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
          <p className="font-semibold text-slate-800 dark:text-slate-200 mb-4">知识板块表现</p>
          <div className="space-y-3">
            {weakCategories.map(stat => (
              <div key={stat.category}>
                <div className="flex items-center justify-between text-sm mb-1">
                  <span className="text-slate-700 dark:text-slate-300">
                    {stat.category}
                    <span className="ml-2 text-xs text-slate-400">
                      {stat.questionCount} 题 · {stat.score}/{stat.fullScore} 分
                    </span>
                  </span>
                  <span
                    className={`font-medium ${
                      stat.rate >= 80
                        ? 'text-emerald-600 dark:text-emerald-400'
                        : stat.rate >= 60
                          ? 'text-amber-600 dark:text-amber-400'
                          : 'text-red-600 dark:text-red-400'
                    }`}
                  >
                    {stat.rate}%
                  </span>
                </div>
                <div className="h-2 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${
                      stat.rate >= 80 ? 'bg-emerald-500' : stat.rate >= 60 ? 'bg-amber-500' : 'bg-red-500'
                    }`}
                    style={{ width: `${Math.min(100, stat.rate)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {report.sectionScores && report.sectionScores.length > 0 && (
        <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
          <p className="font-semibold text-slate-800 dark:text-slate-200 mb-4">按小节得分</p>
          <div className="space-y-3">
            {report.sectionScores.map(section => (
              <div key={section.title + section.level}>
                <div className="flex items-center justify-between text-sm mb-1">
                  <span className="text-slate-700 dark:text-slate-300">
                    {section.title}
                    {section.subtitle ? (
                      <span className="ml-2 text-xs text-slate-400">{section.subtitle}</span>
                    ) : null}
                    <span className="ml-2 text-xs text-slate-400">
                      {section.questionCount} 题 · {section.score}/{section.fullScore} 分
                    </span>
                  </span>
                  <span
                    className={`font-medium ${
                      section.scoreRate >= 80
                        ? 'text-emerald-600 dark:text-emerald-400'
                        : section.scoreRate >= 60
                          ? 'text-amber-600 dark:text-amber-400'
                          : 'text-red-600 dark:text-red-400'
                    }`}
                  >
                    {section.scoreRate}%
                  </span>
                </div>
                <div className="h-2 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                  <div
                    className={`h-full rounded-full ${
                      section.scoreRate >= 80
                        ? 'bg-emerald-500'
                        : section.scoreRate >= 60
                          ? 'bg-amber-500'
                          : 'bg-red-500'
                    }`}
                    style={{ width: `${Math.min(100, section.scoreRate)}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="bg-white dark:bg-slate-800 rounded-2xl p-6">
        <p className="font-semibold text-slate-800 dark:text-slate-200 mb-4">逐题解析</p>
        <div className="space-y-5">
          {(report.answers ?? []).map(answer => {
            const reference = referenceMap.get(answer.questionIndex);
            const scoreRate = answer.score ?? 0;
            return (
              <div
                key={answer.questionIndex}
                className="border border-slate-200 dark:border-slate-700 rounded-xl p-4"
              >
                <div className="flex items-center gap-2 flex-wrap mb-3">
                  <span className="px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded text-xs">
                    第 {answer.questionIndex + 1} 题
                  </span>
                  {answer.category && (
                    <span className="px-2 py-0.5 bg-indigo-50 dark:bg-indigo-900/40 text-indigo-600 dark:text-indigo-300 rounded text-xs">
                      {answer.category}
                    </span>
                  )}
                  {sectionTitlesOf(report.paperTree, answer.questionIndex).slice(-1).map(title => (
                    <span
                      key={title}
                      className="px-2 py-0.5 bg-slate-50 dark:bg-slate-900/60 text-slate-500 dark:text-slate-400 rounded text-xs"
                    >
                      {title}
                    </span>
                  ))}
                  <span
                    className={`px-2 py-0.5 rounded text-xs font-medium ${
                      scoreRate >= 80
                        ? 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-400'
                        : scoreRate >= 60
                          ? 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400'
                          : 'bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400'
                    }`}
                  >
                    得分率 {scoreRate}%
                  </span>
                </div>
                <p className="text-slate-800 dark:text-slate-100 whitespace-pre-wrap mb-3 text-sm leading-relaxed">
                  {answer.question}
                </p>
                {(() => {
                  const options = optionsMap.get(answer.questionIndex) ?? [];
                  if (options.length < 2) return null;
                  const correctKeys = (reference?.referenceAnswer ?? '')
                    .toUpperCase()
                    .split('')
                    .filter(key => options.some(option => option.key === key));
                  const chosenKeys = (answer.userAnswer ?? '').toUpperCase().split('');
                  return (
                    <div className="space-y-2 mb-3">
                      {options.map(option => {
                        const isCorrect = correctKeys.includes(option.key);
                        const isChosen = chosenKeys.includes(option.key);
                        const tone = isCorrect
                          ? 'border-emerald-400 bg-emerald-50 dark:bg-emerald-900/20'
                          : isChosen
                            ? 'border-red-300 bg-red-50 dark:bg-red-900/20'
                            : 'border-slate-200 dark:border-slate-700';
                        return (
                          <div key={option.key} className={`flex items-start gap-3 rounded-lg border px-3 py-2 text-sm ${tone}`}>
                            <span className="min-w-[18px] font-medium text-slate-500 dark:text-slate-400">
                              {option.key}
                            </span>
                            <span className="flex-1 text-slate-700 dark:text-slate-200 whitespace-pre-wrap">
                              {option.text}
                            </span>
                            {isCorrect && (
                              <span className="text-xs text-emerald-600 dark:text-emerald-400">正确答案</span>
                            )}
                            {isChosen && !isCorrect && (
                              <span className="text-xs text-red-600 dark:text-red-400">你的选择</span>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  );
                })()}
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
                  <div className="p-3 bg-slate-50 dark:bg-slate-900/50 rounded-lg">
                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                      我的作答
                    </p>
                    <p className="text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                      {answer.userAnswer?.trim() ? answer.userAnswer : '（未作答）'}
                    </p>
                  </div>
                  <div className="p-3 bg-emerald-50/60 dark:bg-emerald-900/20 rounded-lg">
                    <p className="text-xs font-semibold text-emerald-600 dark:text-emerald-400 mb-1">
                      参考答案
                    </p>
                    <p className="text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                      {reference?.referenceAnswer || '—'}
                    </p>
                    {reference?.keyPoints && reference.keyPoints.length > 0 && (
                      <ul className="mt-2 space-y-1">
                        {reference.keyPoints.map((point, index) => (
                          <li key={index} className="text-xs text-slate-500 dark:text-slate-400">
                            · {point}
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                </div>
                {answer.feedback && (
                  <div className="mt-3 p-3 bg-slate-50 dark:bg-slate-900/50 rounded-lg">
                    <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">
                      AI 评语
                    </p>
                    <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap">
                      {answer.feedback}
                    </p>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      <div className="flex justify-end">
        <button
          onClick={onRetry}
          className="px-5 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors flex items-center gap-2"
        >
          <RotateCcw className="w-4 h-4" />
          再练一遍
        </button>
      </div>
    </div>
  );
}
