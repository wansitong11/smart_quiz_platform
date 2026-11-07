import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { resumeApi } from '../api/resume';
import { historyApi, type InterviewDetail, type ResumeDetail } from '../api/history';
import { interviewApi } from '../api/interview';
import {
  isAnswerEmpty,
  toSubmitAnswer,
  type AnswerValue,
} from '../components/QuestionAnswerInput';
import type { InterviewQuestion, PaperTree } from '../types/interview';
import { summarizeCategories, summarizePaperScore } from '../utils/paperScore';

export type PaperAnalysisStage =
  | 'upload'
  | 'input'
  | 'recognition'
  | 'extracting'
  | 'ready'
  | 'practice'
  | 'grading'
  | 'report';

const ANSWERS_STORAGE_PREFIX = 'smart-quiz-paper-answers:';
const POLL_INTERVAL_MS = 2500;

interface ReportQuestionLike {
  questionIndex: number;
  category?: string | null;
  fullScore?: number | null;
}

/**
 * 试卷分析页状态机：负责 URL 现场恢复、轮询、作答暂存与各阶段动作。
 */
export function usePaperAnalysis() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [stage, setStage] = useState<PaperAnalysisStage>('upload');
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
          // 兼容早期版本把答案存成纯字符串的格式。
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
    if (detail.inputStatus && detail.inputStatus !== 'COMPLETED') {
      if (detail.inputStatus === 'FAILED') {
        setError(detail.inputError || '文件解析失败，请重试');
      } else {
        setError('');
      }
      setStage('input');
      return;
    }
    const latest = detail.analyses?.[0];
    const recognition = detail.recognitionStatus;
    // 扫描件先进入识别 / 校对；确认切题后 recognitionStatus=EXTRACTING，回到切题进度。
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

  useEffect(() => {
    if (stage !== 'input' || !paperId || paperDetail?.inputStatus === 'FAILED') return;
    const timer = window.setInterval(() => {
      loadPaper(paperId).catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [stage, paperId, paperDetail?.inputStatus, loadPaper]);

  useEffect(() => {
    if (stage !== 'extracting' || !paperId) return;
    const timer = window.setInterval(() => {
      loadPaper(paperId).catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [stage, paperId, loadPaper]);

  useEffect(() => {
    if (stage !== 'grading' || !sessionId) return;
    const timer = window.setInterval(() => {
      loadReport(sessionId).catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [stage, sessionId, loadReport]);

  useEffect(() => {
    if (!sessionId || stage !== 'practice') return;
    localStorage.setItem(ANSWERS_STORAGE_PREFIX + sessionId, JSON.stringify(answers));
  }, [answers, sessionId, stage]);

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

  const handleRetryInput = async () => {
    if (!paperId) return;
    setError('');
    setPaperDetail(previous => previous
      ? { ...previous, inputStatus: 'PENDING', inputError: null }
      : previous);
    setStage('input');
    try {
      await resumeApi.reanalyze(paperId);
      await loadPaper(paperId);
    } catch (err) {
      setError(err instanceof Error ? err.message : '重新解析失败');
      void loadPaper(paperId).catch(() => undefined);
    }
  };

  /**
   * 从试卷进入时按试卷 ID 开；从报告进入时按会话对应的试卷开。
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
    const blank = questions.filter(question => isAnswerEmpty(answers[question.questionIndex])).length;
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

  const handleRecognitionConfirmed = () => {
    if (!paperId) return;
    setStage('extracting');
    void loadPaper(paperId);
  };

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
  const answeredCount = questions.filter(
    question => !isAnswerEmpty(answers[question.questionIndex])
  ).length;
  const totalFullScore = questions.reduce((sum, question) => sum + (question.fullScore ?? 0), 0);

  return {
    stage,
    paperId,
    paperDetail,
    sessionId,
    sessionPaperId,
    questions,
    paperTree,
    answers,
    report,
    submitting,
    error,
    questionRefs,
    scoreSummary,
    weakCategories,
    answeredCount,
    totalFullScore,
    setAnswers,
    openPaper: (id: number) => setSearchParams({ paper: String(id) }),
    handleReanalyze,
    handleRetryInput,
    handleStartPractice,
    handleSubmit,
    handleRecognitionConfirmed,
    scrollToQuestion,
  };
}
