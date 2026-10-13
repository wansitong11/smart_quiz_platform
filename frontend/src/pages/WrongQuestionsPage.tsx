import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, ChevronDown, ChevronRight, Loader2, RefreshCw } from 'lucide-react';
import quizApi, { type QuizSubject, type WrongQuestionItem } from '../api/quiz';
import { getErrorMessage } from '../api/request';

/** 非学科来源的 skillId → 中文展示名（资料库出题等） */
const NON_SUBJECT_SKILL_LABELS: Record<string, string> = {
  'knowledge-base': '知识库',
  'custom': '自定义',
};

export default function WrongQuestionsPage() {
  const navigate = useNavigate();
  const [allItems, setAllItems] = useState<WrongQuestionItem[]>([]);
  const [subjects, setSubjects] = useState<QuizSubject[]>([]);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const [skillFilter, setSkillFilter] = useState<string>('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const keyOf = (item: WrongQuestionItem) => `${item.sessionId}#${item.questionIndex}`;

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const [wrongList, subjectList] = await Promise.all([
          quizApi.wrongQuestions(undefined, 300),
          quizApi.listSubjects(),
        ]);
        if (!cancelled) {
          setAllItems(wrongList);
          setSubjects(subjectList);
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

  // 分类按钮的数据源必须是「全量错题」：用过滤后的列表会导致选完某个学科后按钮本身消失
  const skills = useMemo(
    () => Array.from(new Set(allItems.map(item => item.skillId))).filter(Boolean),
    [allItems],
  );
  const items = useMemo(
    () => (skillFilter ? allItems.filter(item => item.skillId === skillFilter) : allItems),
    [allItems, skillFilter],
  );
  // 错题可能来自学科测验，也可能来自资料库出题：后者没有对应「学科」，
  // 直接展示 skillId 会露出 knowledge-base 这种英文标识，这里做一次中文映射。
  const skillLabel = (id: string) =>
    subjects.find(subject => subject.id === id)?.name
    || NON_SUBJECT_SKILL_LABELS[id]
    || id;

  const toggleSelect = (key: string) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleExpand = (key: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const handleRetry = async () => {
    // 用全量列表取选中项，避免切换学科筛选后已勾选的题被丢掉
    const refs = allItems
      .filter(item => selected.has(keyOf(item)))
      .map(item => ({ sessionId: item.sessionId, questionIndex: item.questionIndex }));
    if (refs.length === 0) {
      setError('请先勾选要重练的错题');
      return;
    }
    setStarting(true);
    setError('');
    try {
      const session = await quizApi.retryWrongQuestions(refs.slice(0, 12));
      navigate(`/quiz/${session.sessionId}`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setStarting(false);
    }
  };

  return (
    <div className="max-w-4xl mx-auto">
      <button
        onClick={() => navigate('/quiz-hub')}
        className="flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-200 mb-4"
      >
        <ArrowLeft className="w-4 h-4" />
        返回测验中心
      </button>

      <div className="flex items-center justify-between gap-3 mb-5">
        <div>
          <h1 className="text-lg font-bold text-slate-800 dark:text-white">错题本</h1>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            共 {allItems.length} 道错题，勾选后可生成一次「错题重练」（最多 12 题）
          </p>
        </div>
        <button
          onClick={() => void handleRetry()}
          disabled={starting || selected.size === 0}
          className="flex items-center gap-2 px-4 py-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white text-sm font-medium disabled:opacity-50"
        >
          {starting ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
          错题重练（{selected.size}）
        </button>
      </div>

      {skills.length > 0 && (
        <div className="flex flex-wrap gap-2 mb-4">
          <FilterChip active={skillFilter === ''} onClick={() => setSkillFilter('')}>全部学科</FilterChip>
          {skills.map(skill => (
            <FilterChip key={skill} active={skillFilter === skill} onClick={() => setSkillFilter(skill)}>
              {skillLabel(skill)}
            </FilterChip>
          ))}
        </div>
      )}

      {error && (
        <div className="mb-4 px-4 py-3 rounded-xl bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-16">
          <Loader2 className="w-6 h-6 animate-spin text-slate-400" />
        </div>
      ) : items.length === 0 ? (
        <div className="py-16 text-center text-sm text-slate-400">
          {allItems.length === 0
            ? '还没有错题。做完一次模拟测验后，答错的题会自动收进这里。'
            : '该学科暂无错题，可切换其它学科查看。'}
        </div>
      ) : (
        <div className="space-y-3">
          {items.map(item => {
            const key = keyOf(item);
            const isOpen = expanded.has(key);
            const isSelected = selected.has(key);
            return (
              <div
                key={key}
                className={`bg-white dark:bg-slate-800 rounded-2xl border p-4 transition ${
                  isSelected ? 'border-primary-300 dark:border-primary-700' : 'border-slate-100 dark:border-slate-700'
                }`}
              >
                <div className="flex items-start gap-3">
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onChange={() => toggleSelect(key)}
                    className="mt-1 w-4 h-4 accent-primary-500"
                  />
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap mb-1.5">
                      <span className="px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-xs text-slate-600 dark:text-slate-300">
                        {item.category || item.skillId}
                      </span>
                      {item.fullScore != null && (
                        <span className="px-2 py-0.5 rounded bg-rose-50 dark:bg-rose-900/30 text-xs text-rose-600 dark:text-rose-300">
                          得 {item.score ?? 0} / {item.fullScore} 分
                        </span>
                      )}
                      <span className="px-2 py-0.5 rounded bg-amber-50 dark:bg-amber-900/30 text-xs text-amber-600 dark:text-amber-300">
                        错 {item.wrongCount} 次
                      </span>
                      {item.lastWrongAt && (
                        <span className="text-xs text-slate-400">
                          {item.lastWrongAt.replace('T', ' ').slice(0, 16)}
                        </span>
                      )}
                    </div>
                    <p className="text-sm text-slate-800 dark:text-slate-100 whitespace-pre-wrap leading-relaxed">
                      {item.stem}
                    </p>

                    <button
                      onClick={() => toggleExpand(key)}
                      className="mt-2 flex items-center gap-1 text-xs text-primary-600 dark:text-primary-300"
                    >
                      {isOpen ? <ChevronDown className="w-3 h-3" /> : <ChevronRight className="w-3 h-3" />}
                      {isOpen ? '收起解析' : '查看我的答案与解析'}
                    </button>

                    {isOpen && (
                      <div className="mt-3 space-y-2 text-sm">
                        <div className="px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-900/50">
                          <span className="text-xs text-slate-400">我的答案</span>
                          <p className="text-slate-700 dark:text-slate-200 whitespace-pre-wrap">
                            {item.userAnswer || '（未作答）'}
                          </p>
                        </div>
                        <div className="px-3 py-2 rounded-lg bg-emerald-50 dark:bg-emerald-900/20">
                          <span className="text-xs text-emerald-600 dark:text-emerald-400">参考答案</span>
                          <p className="text-slate-700 dark:text-slate-200 whitespace-pre-wrap">
                            {item.referenceAnswer || '（暂无）'}
                          </p>
                        </div>
                        {item.analysis && (
                          <div className="px-3 py-2 rounded-lg bg-indigo-50 dark:bg-indigo-900/20">
                            <span className="text-xs text-indigo-600 dark:text-indigo-400">解析</span>
                            <p className="text-slate-700 dark:text-slate-200 whitespace-pre-wrap">
                              {item.analysis}
                            </p>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function FilterChip({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      className={`px-3 py-1.5 rounded-lg border text-xs transition ${
        active
          ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300'
          : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300'
      }`}
    >
      {children}
    </button>
  );
}
