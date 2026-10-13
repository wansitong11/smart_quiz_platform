import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Loader2, Send } from 'lucide-react';
import quizApi from '../api/quiz';
import { historyApi, type InterviewDetail } from '../api/history';
import QuestionAnswerInput, {
  EMPTY_ANSWER,
  isAnswerEmpty,
  toSubmitAnswer,
  type AnswerValue,
} from '../components/QuestionAnswerInput';
import PaperTreeView from '../components/PaperTreeView';
import InterviewDetailPanel from '../components/InterviewDetailPanel';
import type {
  BatchSubmitAnswerItem,
  InterviewQuestion,
  InterviewSession,
  PaperNode,
  PaperTree,
} from '../types/interview';

type Mode = 'attempt' | 'report';

interface QuizPageProps {
  sessionId: string;
  mode: Mode;
  onBack: () => void;
  onFinished: (sessionId: string) => void;
  /** 返回按钮文案（资料测验复用本页时用「返回题库」等） */
  backLabel?: string;
}

/** 从层级树里取出全部题目（按题号顺序），供答题卡与交卷使用 */
function flattenQuestions(tree?: PaperTree | null): InterviewQuestion[] {
  if (!tree?.root) return [];
  const result: InterviewQuestion[] = [];
  const walk = (node: PaperNode) => {
    if (node.type === 'QUESTION' && node.question) {
      result.push(node.question);
    }
    (node.children ?? []).forEach(walk);
  };
  walk(tree.root);
  return result.sort((a, b) => a.questionIndex - b.questionIndex);
}

export default function QuizPage({ sessionId, mode, onBack, onFinished, backLabel }: QuizPageProps) {
  if (mode === 'report') {
    return <QuizReport sessionId={sessionId} onBack={onBack} backLabel={backLabel} />;
  }
  return (
    <QuizAttempt
      sessionId={sessionId}
      onBack={onBack}
      onFinished={onFinished}
      backLabel={backLabel}
    />
  );
}

function QuizAttempt({
  sessionId,
  onBack,
  onFinished,
  backLabel,
}: {
  sessionId: string;
  onBack: () => void;
  onFinished: (sessionId: string) => void;
  backLabel?: string;
}) {
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [answers, setAnswers] = useState<Record<number, AnswerValue>>({});
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const questionRefs = useRef<Record<number, HTMLDivElement | null>>({});

  const questions = useMemo(() => flattenQuestions(session?.paperTree), [session?.paperTree]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const data = await quizApi.getSession(sessionId);
        if (!cancelled) setSession(data);
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : '加载测验失败');
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  const handleAnswerChange = useCallback((questionIndex: number, value: AnswerValue) => {
    setAnswers(prev => ({ ...prev, [questionIndex]: value }));
  }, []);

  const answeredCount = questions.filter(q => !isAnswerEmpty(answers[q.questionIndex])).length;
  const blankCount = questions.length - answeredCount;
  const totalScore = questions.reduce((sum, q) => sum + (q.fullScore ?? 0), 0);

  const handleSubmit = async () => {
    const message = blankCount > 0
      ? `还有 ${blankCount} 道题没有作答，交卷后这些题按 0 分计。确定交卷吗？`
      : '确定交卷吗？交卷后将立即开始批改。';
    if (!window.confirm(message)) return;

    setSubmitting(true);
    setError('');
    try {
      const items: BatchSubmitAnswerItem[] = questions.map(question => {
        const { selectedKeys, answer } = toSubmitAnswer(answers[question.questionIndex]);
        return {
          questionIndex: question.questionIndex,
          questionType: question.questionType ?? undefined,
          selectedKeys,
          answer,
        };
      });
      await quizApi.submit(sessionId, items);
      onFinished(sessionId);
    } catch (err) {
      setError(err instanceof Error ? err.message : '交卷失败');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <Centered>
        <Loader2 className="w-6 h-6 animate-spin text-slate-400" />
        <p className="mt-3 text-sm text-slate-500">正在加载测验…</p>
      </Centered>
    );
  }

  if (error && !session) {
    return (
      <Centered>
        <p className="text-sm text-rose-600 dark:text-rose-300">{error}</p>
        <BackButton onClick={onBack} />
      </Centered>
    );
  }

  return (
    <div className="max-w-4xl mx-auto pb-16">
      <div className="flex items-start justify-between gap-4 mb-6">
        <div>
          <button
            onClick={onBack}
            className="flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-200 mb-2"
          >
            <ArrowLeft className="w-4 h-4" />
            {backLabel ?? '返回测验中心'}
          </button>
          <h1 className="text-xl font-bold text-slate-800 dark:text-white">
            {session?.paperTree?.root?.title || '模拟测验'}
          </h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            共 {questions.length} 题 · 卷面 {totalScore} 分 · 已作答 {answeredCount}/{questions.length}
          </p>
        </div>
      </div>

      {/* 答题卡 */}
      <div className="flex flex-wrap gap-1.5 mb-6">
        {questions.map(question => (
          <button
            key={question.questionIndex}
            onClick={() => questionRefs.current[question.questionIndex]?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
            className={`w-8 h-8 rounded-lg text-xs font-medium border transition-colors ${
              !isAnswerEmpty(answers[question.questionIndex])
                ? 'bg-primary-500 text-white border-primary-500'
                : 'bg-white dark:bg-slate-700 text-slate-500 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-primary-400'
            }`}
          >
            {question.questionIndex + 1}
          </button>
        ))}
      </div>

      {session?.paperTree ? (
        <PaperTreeView tree={session.paperTree} answers={answers} onAnswerChange={handleAnswerChange} />
      ) : (
        questions.map(question => (
          <motion.div
            key={question.questionIndex}
            ref={element => {
              questionRefs.current[question.questionIndex] = element;
            }}
            className="bg-white dark:bg-slate-800 rounded-2xl p-6 mb-4"
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
              onChange={value => handleAnswerChange(question.questionIndex, value)}
            />
          </motion.div>
        ))
      )}

      {error && (
        <div className="mt-4 px-4 py-3 rounded-xl bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
          {error}
        </div>
      )}

      <div className="flex justify-end mt-6">
        <button
          onClick={handleSubmit}
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

function QuizReport({ sessionId, onBack, backLabel }: {
  sessionId: string;
  onBack: () => void;
  backLabel?: string;
}) {
  const navigate = useNavigate();
  const [detail, setDetail] = useState<InterviewDetail | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [pollTimedOut, setPollTimedOut] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let timer: number | undefined;
    // 最多轮询 40 次 × 3 秒 = 2 分钟，避免批改卡住时页面无限转圈
    const MAX_POLLS = 40;

    const load = async (attempt: number) => {
      try {
        const data = await historyApi.getInterviewDetail(sessionId)
          .catch(async () => quizApi.getDetail(sessionId));
        if (cancelled) return;
        setDetail(data);
        setLoading(false);

        const submitted = data.status === 'COMPLETED' || data.status === 'EVALUATED';
        const grading = submitted
          && data.evaluateStatus !== 'COMPLETED' && data.evaluateStatus !== 'FAILED';
        if (!grading) {
          return;
        }
        if (attempt >= MAX_POLLS) {
          setPollTimedOut(true);
          return;
        }
        timer = window.setTimeout(() => void load(attempt + 1), 3000);
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : '加载报告失败');
          setLoading(false);
        }
      }
    };
    void load(0);
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [sessionId]);

  if (loading) {
    return (
      <Centered>
        <Loader2 className="w-6 h-6 animate-spin text-slate-400" />
        <p className="mt-3 text-sm text-slate-500">正在加载报告…</p>
      </Centered>
    );
  }

  if (error || !detail) {
    return (
      <Centered>
        <p className="text-sm text-rose-600 dark:text-rose-300">{error || '报告不存在'}</p>
        <BackButton onClick={onBack} />
      </Centered>
    );
  }

  const submitted = detail.status === 'COMPLETED' || detail.status === 'EVALUATED';
  const grading = submitted
    && detail.evaluateStatus !== 'COMPLETED' && detail.evaluateStatus !== 'FAILED';

  return (
    <div className="max-w-4xl mx-auto pb-16">
      <button
        onClick={onBack}
        className="flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-200 mb-4"
      >
        <ArrowLeft className="w-4 h-4" />
        {backLabel ?? '返回测验中心'}
      </button>

      {/* 未交卷：不能说成"正在批改"，要给回作答页的入口 */}
      {!submitted && (
        <div className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 p-8 text-center">
          <p className="text-base font-semibold text-slate-800 dark:text-white">本次测验还没有交卷</p>
          <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
            交卷后才会生成批改报告。你可以回到作答页继续答题，已填的答案不会丢。
          </p>
          <div className="mt-5 flex items-center justify-center gap-3">
            <button
              onClick={() => navigate(`/quiz/${sessionId}`, { replace: true })}
              className="px-5 py-2.5 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white text-sm font-medium"
            >
              继续作答
            </button>
            <button
              onClick={onBack}
              className="px-5 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 text-sm text-slate-600 dark:text-slate-300"
            >
              {backLabel ?? '返回测验中心'}
            </button>
          </div>
        </div>
      )}

      {grading && !pollTimedOut && (
        <div className="mb-4 px-4 py-3 rounded-xl bg-amber-50 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300 text-sm flex items-center gap-2">
          <Loader2 className="w-4 h-4 animate-spin" />
          正在批改，主观题需要一点时间，页面会自动刷新…
        </div>
      )}
      {grading && pollTimedOut && (
        <div className="mb-4 px-4 py-3 rounded-xl bg-amber-50 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300 text-sm">
          批改时间超过预期，可能仍在进行。可以刷新页面查看最新结果。
        </div>
      )}
      {submitted && detail.evaluateStatus === 'FAILED' && (
        <div className="mb-4 px-4 py-3 rounded-xl bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
          批改失败：{detail.evaluateError || '请稍后重试'}
        </div>
      )}

      {submitted && <InterviewDetailPanel interview={detail} />}
    </div>
  );
}

function Centered({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-[60vh] flex flex-col items-center justify-center text-center px-4">
      {children}
    </div>
  );
}

function BackButton({ onClick }: { onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="mt-4 px-4 py-2 rounded-xl border border-slate-200 dark:border-slate-600 text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 transition"
    >
      返回
    </button>
  );
}
