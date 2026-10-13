import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { BookOpen, Loader2, NotebookPen, RefreshCw, Sparkles, Target } from 'lucide-react';
import quizApi, { type QuizSessionMeta, type QuizSubject } from '../api/quiz';
import { getErrorMessage } from '../api/request';
import { useAuth } from '../context/AuthContext';

const DIFFICULTIES = [
  { value: 'junior', label: '简单', hint: '直接考查基本概念，一步即可作答' },
  { value: 'mid', label: '中等', hint: '需要两步以上推理，或对概念做简单变形' },
  { value: 'senior', label: '偏难', hint: '跨知识点综合，需要严谨推理' },
];

const QUESTION_COUNTS = [5, 8, 10, 12];

const TYPE_PRESETS = [
  { value: 'auto', label: '自动配比', types: [] as string[] },
  { value: 'objective', label: '以客观题为主', types: ['SINGLE_CHOICE', 'FILL_BLANK', 'MULTI_CHOICE'] },
  { value: 'subjective', label: '以主观题为主', types: ['SHORT_ANSWER', 'WRITING', 'READING'] },
];

export default function QuizHubPage() {
  const navigate = useNavigate();
  const { user } = useAuth();

  const [subjects, setSubjects] = useState<QuizSubject[]>([]);
  const [history, setHistory] = useState<QuizSessionMeta[]>([]);
  const [wrongCount, setWrongCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [error, setError] = useState('');

  const [skillId, setSkillId] = useState('');
  const [difficulty, setDifficulty] = useState('mid');
  const [questionCount, setQuestionCount] = useState(8);
  const [categories, setCategories] = useState<string[]>([]);
  const [typePreset, setTypePreset] = useState('auto');

  const currentSubject = useMemo(
    () => subjects.find(subject => subject.id === skillId),
    [subjects, skillId],
  );

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [subjectList, historyList, wrongList] = await Promise.all([
          quizApi.listSubjects(),
          quizApi.history(),
          quizApi.wrongQuestions(undefined, 200),
        ]);
        if (cancelled) return;
        setSubjects(subjectList);
        setHistory(historyList);
        setWrongCount(wrongList.length);
        if (subjectList.length > 0) {
          setSkillId(current => current || subjectList[0].id);
        }
      } catch (err) {
        if (!cancelled) setError(getErrorMessage(err));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const toggleCategory = (key: string) => {
    setCategories(prev => (prev.includes(key) ? prev.filter(item => item !== key) : [...prev, key]));
  };

  const handleStart = async () => {
    if (!skillId) {
      setError('请先选择学科');
      return;
    }
    setCreating(true);
    setError('');
    try {
      const types = TYPE_PRESETS.find(preset => preset.value === typePreset)?.types ?? [];
      const session = await quizApi.createQuiz({
        skillId,
        difficulty,
        questionCount,
        categories: categories.length > 0 ? categories : undefined,
        questionTypes: types.length > 0 ? types : undefined,
      });
      navigate(`/quiz/${session.sessionId}`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setCreating(false);
    }
  };

  const handleRetry = async (sessionId: string) => {
    setRetrying(sessionId);
    setError('');
    try {
      const session = await quizApi.retry(sessionId);
      navigate(`/quiz/${session.sessionId}`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setRetrying(null);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <Loader2 className="w-6 h-6 animate-spin text-slate-400" />
      </div>
    );
  }

  return (
    <div className="max-w-5xl mx-auto space-y-6">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-primary-500 to-indigo-500 flex items-center justify-center">
          <Sparkles className="w-5 h-5 text-white" />
        </div>
        <div>
          <h1 className="text-lg font-bold text-slate-800 dark:text-white">模拟测验</h1>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            选学科与知识点，AI 组一份卷子，整卷作答后一次交卷批改
          </p>
        </div>
        <button
          onClick={() => navigate('/wrong-questions')}
          className="ml-auto flex items-center gap-2 px-4 py-2 rounded-xl border border-slate-200 dark:border-slate-600 text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 transition"
        >
          <NotebookPen className="w-4 h-4" />
          错题本
          {wrongCount > 0 && (
            <span className="px-1.5 py-0.5 rounded-full bg-rose-100 dark:bg-rose-900/40 text-rose-600 dark:text-rose-300 text-xs">
              {wrongCount}
            </span>
          )}
        </button>
      </div>

      {error && (
        <div className="px-4 py-3 rounded-xl bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
          {error}
        </div>
      )}

      {/* 组卷配置 */}
      <section className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 p-6 space-y-5">
        <div>
          <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">学科</p>
          <div className="flex flex-wrap gap-2">
            {subjects.map(subject => (
              <button
                key={subject.id}
                onClick={() => {
                  setSkillId(subject.id);
                  setCategories([]);
                }}
                className={`flex items-center gap-2 px-4 py-2.5 rounded-xl border text-sm font-medium transition ${
                  skillId === subject.id
                    ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300'
                    : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-primary-300'
                }`}
              >
                <BookOpen className="w-4 h-4" />
                {subject.name}
              </button>
            ))}
          </div>
        </div>

        {currentSubject?.categories && currentSubject.categories.length > 0 && (
          <div>
            <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">
              知识板块
              <span className="ml-2 text-xs font-normal text-slate-400">
                不选则覆盖全科
              </span>
            </p>
            <div className="flex flex-wrap gap-2">
              {currentSubject.categories.map(category => (
                <button
                  key={category.key}
                  onClick={() => toggleCategory(category.key)}
                  className={`px-3 py-1.5 rounded-lg border text-xs transition ${
                    categories.includes(category.key)
                      ? 'border-indigo-400 bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600 dark:text-indigo-300'
                      : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-indigo-300'
                  }`}
                >
                  {category.label}
                </button>
              ))}
            </div>
          </div>
        )}

        <div className="grid gap-5 sm:grid-cols-3">
          <div>
            <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">难度</p>
            <div className="flex gap-2">
              {DIFFICULTIES.map(item => (
                <button
                  key={item.value}
                  title={item.hint}
                  onClick={() => setDifficulty(item.value)}
                  className={`flex-1 px-3 py-2 rounded-lg border text-xs transition ${
                    difficulty === item.value
                      ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300'
                      : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300'
                  }`}
                >
                  {item.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">题量</p>
            <div className="flex gap-2">
              {QUESTION_COUNTS.map(count => (
                <button
                  key={count}
                  onClick={() => setQuestionCount(count)}
                  className={`flex-1 px-3 py-2 rounded-lg border text-xs transition ${
                    questionCount === count
                      ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300'
                      : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300'
                  }`}
                >
                  {count} 题
                </button>
              ))}
            </div>
          </div>

          <div>
            <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">题型</p>
            <div className="flex gap-2">
              {TYPE_PRESETS.map(preset => (
                <button
                  key={preset.value}
                  onClick={() => setTypePreset(preset.value)}
                  className={`flex-1 px-2 py-2 rounded-lg border text-xs transition ${
                    typePreset === preset.value
                      ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300'
                      : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300'
                  }`}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>
        </div>

        <button
          onClick={handleStart}
          disabled={creating || !skillId}
          className="w-full py-3 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white font-medium disabled:opacity-50 flex items-center justify-center gap-2"
        >
          {creating ? <Loader2 className="w-4 h-4 animate-spin" /> : <Target className="w-4 h-4" />}
          {creating ? '正在组卷，请稍候…' : '开始模拟测验'}
        </button>
        <p className="text-xs text-slate-400 dark:text-slate-500 text-center">
          组卷需要调用模型，通常 10~30 秒
          {user?.displayName ? ` · ${user.displayName}` : ''}
        </p>
      </section>

      {/* 最近测验 */}
      {history.length > 0 && (
        <section className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 p-6">
          <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">最近测验</h2>
          <div className="divide-y divide-slate-100 dark:divide-slate-700">
            {history.slice(0, 5).map(item => (
              <div key={item.sessionId} className="py-3 flex items-center gap-3">
                <div className="min-w-0 flex-1">
                  <p className="text-sm text-slate-800 dark:text-white truncate">
                    {item.subjectCategory || item.skillId}
                    <span className="ml-2 text-xs text-slate-400">{sourceLabel(item.sourceType)}</span>
                  </p>
                  <p className="text-xs text-slate-400 mt-0.5">
                    {item.totalQuestions} 题 · {(item.createdAt || '').replace('T', ' ').slice(0, 16)}
                  </p>
                </div>
                {item.overallScore != null && (
                  <span className="text-sm font-medium text-primary-600 dark:text-primary-300">
                    {item.overallScore} 分
                  </span>
                )}
                <button
                  onClick={() => navigate(isSubmitted(item)
                    ? `/quiz/${item.sessionId}/report`
                    : `/quiz/${item.sessionId}`)}
                  className="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-600 text-xs text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700"
                >
                  {isSubmitted(item) ? '看报告' : '继续作答'}
                </button>
                {isSubmitted(item) && (
                  <button
                    onClick={() => void handleRetry(item.sessionId)}
                    disabled={retrying === item.sessionId}
                    title="用同一份卷子再练一遍（不重新出题）"
                    className="flex items-center gap-1 px-3 py-1.5 rounded-lg border border-primary-200 dark:border-primary-800 text-xs text-primary-600 dark:text-primary-300 hover:bg-primary-50 dark:hover:bg-primary-900/30 disabled:opacity-50"
                  >
                    {retrying === item.sessionId
                      ? <Loader2 className="w-3 h-3 animate-spin" />
                      : <RefreshCw className="w-3 h-3" />}
                    原题重测
                  </button>
                )}
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function sourceLabel(sourceType?: string | null): string {
  switch (sourceType) {
    case 'QUIZ':
      return '模拟测验';
    case 'QUIZ_WRONG':
      return '错题重练';
    case 'PAPER':
      return '试卷分析';
    case 'KNOWLEDGE_BASE':
      return '资料出题';
    default:
      return '练习';
  }
}

/** 只有交过卷的会话才有报告可看，未交卷应该回到作答页 */
function isSubmitted(item: QuizSessionMeta): boolean {
  return item.status === 'COMPLETED' || item.status === 'EVALUATED';
}
