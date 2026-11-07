import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Archive,
  ArchiveRestore,
  ArrowLeft,
  BookOpenCheck,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Loader2,
  RotateCcw,
  Search,
  Sparkles,
} from 'lucide-react';
import quizApi, {
  type QuizCatalog,
  type WrongQuestionFilters,
  type WrongQuestionItem,
  type WrongQuestionPage,
  type WrongQuestionStatus,
} from '../api/quiz';
import { getErrorMessage } from '../api/request';
import AssetImage from '../components/AssetImage';
import RichPaperText from '../components/RichPaperText';

const STATUS_TABS: Array<{ value: WrongQuestionStatus | ''; label: string }> = [
  { value: 'PENDING', label: '待复习' },
  { value: 'MASTERED', label: '已掌握' },
  { value: 'ARCHIVED', label: '已移除' },
  { value: '', label: '全部' },
];

const PAGE_SIZE = 20;
const MAX_SELECTED = 20;

interface UiFilters {
  examCode: string;
  subjectCode: string;
  knowledgePointKey: string;
  questionType: string;
  status: WrongQuestionStatus | '';
  keyword: string;
}

const EMPTY_FILTERS: UiFilters = {
  examCode: '',
  subjectCode: '',
  knowledgePointKey: '',
  questionType: '',
  status: 'PENDING',
  keyword: '',
};

export default function WrongQuestionsPage() {
  const navigate = useNavigate();
  const [catalog, setCatalog] = useState<QuizCatalog | null>(null);
  const [data, setData] = useState<WrongQuestionPage | null>(null);
  const [filters, setFilters] = useState<UiFilters>(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [reloadKey, setReloadKey] = useState(0);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [expandedAnswers, setExpandedAnswers] = useState<Set<string>>(new Set());
  const [expandedMaterials, setExpandedMaterials] = useState<Set<string>>(new Set());

  useEffect(() => {
    let cancelled = false;
    quizApi.catalog()
      .then(result => {
        if (!cancelled) setCatalog(result);
      })
      .catch(err => {
        if (!cancelled) setError(getErrorMessage(err));
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const timer = window.setTimeout(async () => {
      setLoading(true);
      setError('');
      try {
        const result = await quizApi.wrongQuestions(toApiFilters(filters), page, PAGE_SIZE);
        if (!cancelled) setData(result);
      } catch (err) {
        if (!cancelled) setError(getErrorMessage(err));
      } finally {
        if (!cancelled) setLoading(false);
      }
    }, 250);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [filters, page, reloadKey]);

  const exams = catalog?.exams ?? [];
  const subjects = useMemo(() => {
    if (!catalog) return [];
    return filters.examCode
      ? catalog.exams.find(exam => exam.code === filters.examCode)?.subjects ?? []
      : catalog.exams.flatMap(exam => exam.subjects);
  }, [catalog, filters.examCode]);
  const knowledgePoints = useMemo(
    () => subjects.find(subject => subject.code === filters.subjectCode)?.knowledgePoints ?? [],
    [subjects, filters.subjectCode],
  );
  const selectedSubject = subjects.find(subject => subject.code === filters.subjectCode);
  const questionTypes = selectedSubject?.questionTypes ?? [];
  const groups = useMemo(() => groupItems(data?.items ?? []), [data?.items]);
  const statusTotal = useMemo(
    () => (data?.statusCounts ?? []).reduce((sum, item) => sum + item.count, 0),
    [data?.statusCounts],
  );

  const updateFilter = <K extends keyof UiFilters>(key: K, value: UiFilters[K]) => {
    setPage(0);
    setFilters(previous => ({
      ...previous,
      [key]: value,
      ...(key === 'examCode' ? { subjectCode: '', knowledgePointKey: '', questionType: '' } : {}),
      ...(key === 'subjectCode' ? { knowledgePointKey: '', questionType: '' } : {}),
    }));
  };

  const toggleSet = (setter: React.Dispatch<React.SetStateAction<Set<string>>>, key: string) => {
    setter(previous => {
      const next = new Set(previous);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const toggleSelect = (item: WrongQuestionItem) => {
    if (item.status === 'ARCHIVED') return;
    if (!selected.has(item.questionKey) && selected.size >= MAX_SELECTED) {
      setError(`单次最多选择 ${MAX_SELECTED} 道错题`);
      return;
    }
    setError('');
    toggleSet(setSelected, item.questionKey);
  };

  const handleRetrySelected = async () => {
    if (selected.size === 0) {
      setError('请先勾选要重练的错题');
      return;
    }
    setStarting(true);
    setError('');
    try {
      const session = await quizApi.retryWrongQuestions({
        mode: 'SELECTED',
        questionKeys: Array.from(selected),
      });
      navigate(`/quiz/${session.sessionId}`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setStarting(false);
    }
  };

  const handleRandomRetry = async () => {
    setStarting(true);
    setError('');
    try {
      const session = await quizApi.retryWrongQuestions({
        mode: 'RANDOM',
        scope: {
          examCode: filters.examCode || undefined,
          subjectCode: filters.subjectCode || undefined,
          knowledgePointKey: filters.knowledgePointKey || undefined,
          questionType: filters.questionType || undefined,
          status: 'PENDING',
          keyword: filters.keyword.trim() || undefined,
        },
        count: 10,
      });
      navigate(`/quiz/${session.sessionId}`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setStarting(false);
    }
  };

  const handleStatusChange = async (
    item: WrongQuestionItem,
    status: Exclude<WrongQuestionStatus, 'MASTERED'>,
  ) => {
    setError('');
    try {
      await quizApi.updateWrongQuestionStatus(item.questionKey, status);
      setSelected(previous => {
        const next = new Set(previous);
        next.delete(item.questionKey);
        return next;
      });
      setReloadKey(key => key + 1);
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  return (
    <div className="max-w-6xl mx-auto">
      <button
        onClick={() => navigate('/quiz-hub')}
        className="flex items-center gap-1 text-sm text-slate-500 hover:text-slate-700 dark:hover:text-slate-200 mb-4"
      >
        <ArrowLeft className="w-4 h-4" />
        返回测验中心
      </button>

      <div className="flex flex-wrap items-start gap-4 justify-between mb-5">
        <div className="flex items-center gap-2">
          <span className="w-9 h-9 rounded-xl bg-amber-100 dark:bg-amber-900/30 text-amber-600 dark:text-amber-300 flex items-center justify-center">
            <BookOpenCheck className="w-5 h-5" />
          </span>
          <div>
            <h1 className="text-xl font-bold text-slate-800 dark:text-white">错题本</h1>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              原题、解析和掌握进度集中回顾，答对 3 次自动掌握
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            onClick={() => void handleRandomRetry()}
            disabled={starting}
            className="flex items-center gap-2 px-4 py-2 rounded-xl border border-amber-200 dark:border-amber-800 text-sm text-amber-700 dark:text-amber-300 hover:bg-amber-50 dark:hover:bg-amber-900/20 disabled:opacity-50"
          >
            <Sparkles className="w-4 h-4" />
            随机练待复习 10 题
          </button>
          <button
            onClick={() => void handleRetrySelected()}
            disabled={starting || selected.size === 0}
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white text-sm font-medium disabled:opacity-50"
          >
            {starting ? <Loader2 className="w-4 h-4 animate-spin" /> : <RotateCcw className="w-4 h-4" />}
            重练选中（{selected.size}）
          </button>
        </div>
      </div>

      <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_220px] mb-4">
        <div className="flex flex-wrap gap-2">
          {STATUS_TABS.map(tab => {
            const count = tab.value === '' ? statusTotal : countStatus(data, tab.value);
            return (
              <button
                key={tab.value || 'all'}
                onClick={() => updateFilter('status', tab.value)}
                className={`px-3 py-1.5 rounded-lg border text-xs transition ${
                  filters.status === tab.value
                    ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300'
                    : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300'
                }`}
              >
                {tab.label}
                <span className="ml-1.5 opacity-70">{count}</span>
              </button>
            );
          })}
        </div>
        <div className="relative">
          <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            value={filters.keyword}
            onChange={event => updateFilter('keyword', event.target.value)}
            placeholder="搜索题干、考点、科目"
            className="w-full pl-9 pr-3 py-2 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-sm text-slate-700 dark:text-slate-200 outline-none focus:border-primary-400"
          />
        </div>
      </div>

      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4 mb-5">
        <FilterSelect
          value={filters.examCode}
          onChange={value => updateFilter('examCode', value)}
          placeholder="全部考试"
          options={exams.map(exam => ({ value: exam.code, label: exam.name }))}
        />
        <FilterSelect
          value={filters.subjectCode}
          onChange={value => updateFilter('subjectCode', value)}
          placeholder="全部科目"
          options={subjects.map(subject => ({ value: subject.code, label: subject.name }))}
        />
        <FilterSelect
          value={filters.knowledgePointKey}
          onChange={value => updateFilter('knowledgePointKey', value)}
          placeholder="全部考点"
          disabled={!filters.subjectCode}
          options={knowledgePoints.map(point => ({ value: point.code, label: point.label }))}
        />
        <FilterSelect
          value={filters.questionType}
          onChange={value => updateFilter('questionType', value)}
          placeholder="全部题型"
          disabled={!filters.subjectCode}
          options={questionTypes.map(type => ({ value: type.type, label: typeLabel(type.type) }))}
        />
      </div>

      {error && (
        <div className="mb-4 px-4 py-3 rounded-xl bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-20">
          <Loader2 className="w-6 h-6 animate-spin text-slate-400" />
        </div>
      ) : groups.length === 0 ? (
        <EmptyState filtered={hasActiveFilters(filters)} />
      ) : (
        <div className="space-y-6">
          {groups.map(group => (
            <section key={group.key}>
              <div className="flex items-center gap-2 mb-2 px-1">
                <span className="text-sm font-semibold text-slate-700 dark:text-slate-200">
                  {group.examName}
                </span>
                <ChevronRight className="w-3.5 h-3.5 text-slate-400" />
                <span className="text-sm text-slate-600 dark:text-slate-300">{group.subjectName}</span>
                <span className="text-xs text-slate-400">{group.items.length} 道</span>
              </div>
              <div className="space-y-4">
                {group.knowledgePoints.map(point => (
                  <div key={point.key}>
                    <div className="flex items-center gap-2 mb-2 px-1">
                      <span className="text-xs font-medium text-slate-500 dark:text-slate-400">
                        {point.label}
                      </span>
                      <span className="text-xs text-slate-400">{point.items.length} 道</span>
                    </div>
                    <div className="space-y-3">
                      {point.items.map(item => (
                        <WrongQuestionCard
                          key={item.questionKey}
                          item={item}
                          selected={selected.has(item.questionKey)}
                          answerOpen={expandedAnswers.has(item.questionKey)}
                          materialOpen={expandedMaterials.has(item.questionKey)}
                          onToggleSelect={() => toggleSelect(item)}
                          onToggleAnswer={() => toggleSet(setExpandedAnswers, item.questionKey)}
                          onToggleMaterial={() => toggleSet(setExpandedMaterials, item.questionKey)}
                          onStatusChange={status => void handleStatusChange(item, status)}
                        />
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      {data && data.total > 0 && (
        <div className="flex items-center justify-between mt-6 text-xs text-slate-500 dark:text-slate-400">
          <span>
            共 {data.total} 道，第 {data.page + 1} / {Math.max(1, Math.ceil(data.total / data.size))} 页
          </span>
          <div className="flex items-center gap-2">
            <button
              disabled={data.page <= 0}
              onClick={() => setPage(value => Math.max(0, value - 1))}
              className="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-600 disabled:opacity-40"
            >
              上一页
            </button>
            <button
              disabled={(data.page + 1) * data.size >= data.total}
              onClick={() => setPage(value => value + 1)}
              className="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-600 disabled:opacity-40"
            >
              下一页
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function WrongQuestionCard({
  item,
  selected,
  answerOpen,
  materialOpen,
  onToggleSelect,
  onToggleAnswer,
  onToggleMaterial,
  onStatusChange,
}: {
  item: WrongQuestionItem;
  selected: boolean;
  answerOpen: boolean;
  materialOpen: boolean;
  onToggleSelect: () => void;
  onToggleAnswer: () => void;
  onToggleMaterial: () => void;
  onStatusChange: (status: Exclude<WrongQuestionStatus, 'MASTERED'>) => void;
}) {
  const statusTone = statusToneOf(item.status);
  const canSelect = item.status !== 'ARCHIVED';
  return (
    <article
      className={`rounded-2xl border bg-white dark:bg-slate-800 p-4 transition ${
        selected
          ? 'border-primary-300 dark:border-primary-700 ring-1 ring-primary-100 dark:ring-primary-900/40'
          : 'border-slate-200 dark:border-slate-700'
      }`}
    >
      <div className="flex items-start gap-3">
        <input
          type="checkbox"
          checked={selected}
          disabled={!canSelect}
          onChange={onToggleSelect}
          className="mt-1 w-4 h-4 accent-primary-500 disabled:opacity-30"
          aria-label="选择错题"
        />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5 mb-2">
            <span className={`px-2 py-0.5 rounded text-xs ${statusTone}`}>{item.statusLabel}</span>
            <span className="px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-xs text-slate-600 dark:text-slate-300">
              {item.knowledgePointName || '未分类考点'}
            </span>
            <span className="px-2 py-0.5 rounded border border-slate-200 dark:border-slate-600 text-xs text-slate-500 dark:text-slate-400">
              {item.typeLabel}
            </span>
            <span className="px-2 py-0.5 rounded bg-rose-50 dark:bg-rose-900/30 text-xs text-rose-600 dark:text-rose-300">
              累计错 {item.wrongCount} 次
            </span>
            {item.lastScoreRate != null && (
              <span className="px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-xs text-slate-600 dark:text-slate-300">
                上次得分率 {item.lastScoreRate}%
              </span>
            )}
            <MasteryProgress count={item.correctCount} />
            {item.lastWrongAt && (
              <span className="text-xs text-slate-400">{formatTime(item.lastWrongAt)}</span>
            )}
          </div>

          <RichPaperText
            text={item.stem}
            className="text-sm text-slate-800 dark:text-slate-100 leading-relaxed"
          />

          {item.paperId && (item.assetIds?.length ?? 0) > 0 && (
            <div className="mt-3 grid gap-2 sm:grid-cols-2">
              {(item.assetIds ?? []).map(assetId => (
                <AssetImage
                  key={assetId}
                  paperId={item.paperId}
                  assetId={assetId}
                  alt="题目配图"
                  className="max-h-56 w-full rounded-lg border border-slate-200 object-contain dark:border-slate-700"
                />
              ))}
            </div>
          )}

          {item.options.length > 0 && (
            <div className="mt-3 grid gap-1.5 sm:grid-cols-2">
              {item.options.map(option => (
                <div
                  key={option.key}
                  className="rounded-lg border border-slate-100 dark:border-slate-700 bg-slate-50/70 dark:bg-slate-900/30 px-3 py-2 text-sm text-slate-700 dark:text-slate-200"
                >
                  <span className="font-semibold text-slate-500 dark:text-slate-400 mr-1.5">
                    {option.key}.
                  </span>
                  {option.assetId && item.paperId ? (
                    <AssetImage
                      paperId={item.paperId}
                      assetId={option.assetId}
                      alt={`选项 ${option.key}`}
                      className="mt-1 max-h-40 w-full object-contain"
                    />
                  ) : (
                    <RichPaperText
                      text={option.text || (option.assetId ? '（图片选项，源试卷已不可用）' : '（暂无选项文字）')}
                      className="inline"
                    />
                  )}
                </div>
              ))}
            </div>
          )}

          {item.materials.length > 0 && (
            <div className="mt-3 rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/40">
              <button
                onClick={onToggleMaterial}
                className="w-full flex items-center justify-between gap-3 px-3 py-2 text-xs text-slate-500 dark:text-slate-400"
              >
                <span className="flex items-center gap-1.5">
                  {materialOpen ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
                  阅读材料 / 题目上下文
                </span>
                <span>{materialOpen ? '收起' : '展开'}</span>
              </button>
              {materialOpen && (
                <div className="px-3 pb-3 space-y-2">
                  {item.materials.map((material, index) => (
                    <div key={`${material.title ?? 'material'}-${index}`}>
                      {material.title && (
                        <div className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
                          {material.title}
                        </div>
                      )}
                      <RichPaperText
                        text={material.content}
                        className="text-sm text-slate-700 dark:text-slate-300 leading-relaxed"
                      />
                      {item.paperId && (material.assetIds?.length ?? 0) > 0 && (
                        <div className="mt-2 grid gap-2 sm:grid-cols-2">
                          {(material.assetIds ?? []).map(assetId => (
                            <AssetImage
                              key={assetId}
                              paperId={item.paperId}
                              assetId={assetId}
                              alt={`${material.title || '材料'}图片`}
                              className="max-h-56 w-full rounded-lg border border-slate-200 object-contain dark:border-slate-700"
                            />
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          <div className="flex flex-wrap items-center justify-between gap-2 mt-3">
            <button
              onClick={onToggleAnswer}
              className="flex items-center gap-1 text-xs text-primary-600 dark:text-primary-300"
            >
              {answerOpen ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronRight className="w-3.5 h-3.5" />}
              {answerOpen ? '收起答案与解析' : '查看我的答案与解析'}
            </button>
            {item.status === 'ARCHIVED' ? (
              <button
                onClick={() => onStatusChange('PENDING')}
                className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
              >
                <ArchiveRestore className="w-3.5 h-3.5" />
                恢复
              </button>
            ) : (
              <button
                onClick={() => onStatusChange('ARCHIVED')}
                className="flex items-center gap-1 text-xs text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200"
              >
                <Archive className="w-3.5 h-3.5" />
                移出
              </button>
            )}
          </div>

          {answerOpen && (
            <div className="mt-3 grid gap-2 lg:grid-cols-3">
              <ReviewBlock label="我的答案" tone="slate" text={item.userAnswer || '（未作答）'} />
              <ReviewBlock label="参考答案" tone="emerald" text={item.referenceAnswer || '（暂无）'} />
              <ReviewBlock label="解析" tone="indigo" text={item.analysis || '（暂无解析）'} />
            </div>
          )}
        </div>
      </div>
    </article>
  );
}

function MasteryProgress({ count }: { count: number }) {
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded bg-emerald-50 dark:bg-emerald-900/30 text-xs text-emerald-600 dark:text-emerald-300">
      <CheckCircle2 className="w-3 h-3" />
      答对 {Math.min(count, 3)}/3
    </span>
  );
}

function ReviewBlock({
  label,
  tone,
  text,
}: {
  label: string;
  tone: 'slate' | 'emerald' | 'indigo';
  text: string;
}) {
  const toneClass = {
    slate: 'bg-slate-50 dark:bg-slate-900/50 text-slate-500 dark:text-slate-400',
    emerald: 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400',
    indigo: 'bg-indigo-50 dark:bg-indigo-900/20 text-indigo-600 dark:text-indigo-400',
  }[tone];
  return (
    <div className={`rounded-lg px-3 py-2 ${toneClass}`}>
      <div className="text-xs mb-1">{label}</div>
      <RichPaperText
        text={text}
        className="text-sm text-slate-700 dark:text-slate-200 leading-relaxed"
      />
    </div>
  );
}

function FilterSelect({
  value,
  onChange,
  placeholder,
  options,
  disabled,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  options: Array<{ value: string; label: string }>;
  disabled?: boolean;
}) {
  return (
    <select
      value={value}
      disabled={disabled}
      onChange={event => onChange(event.target.value)}
      className="w-full px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-sm text-slate-700 dark:text-slate-200 outline-none focus:border-primary-400 disabled:opacity-50"
    >
      <option value="">{placeholder}</option>
      {options.map(option => (
        <option key={option.value} value={option.value}>{option.label}</option>
      ))}
    </select>
  );
}

function EmptyState({ filtered }: { filtered: boolean }) {
  return (
    <div className="py-20 text-center">
      <BookOpenCheck className="w-10 h-10 mx-auto text-slate-300 dark:text-slate-600 mb-3" />
      <p className="text-sm text-slate-500 dark:text-slate-400">
        {filtered ? '当前筛选条件下没有错题，可清空筛选看看。' : '还没有错题，做完模拟测验后会自动收录。'}
      </p>
    </div>
  );
}

function groupItems(items: WrongQuestionItem[]) {
  const groups = new Map<string, {
    key: string;
    examName: string;
    subjectName: string;
    items: WrongQuestionItem[];
    knowledgePoints: Array<{
      key: string;
      label: string;
      items: WrongQuestionItem[];
    }>;
  }>();
  for (const item of items) {
    const key = `${item.examName ?? '其他考试'}::${item.subjectName ?? '其他科目'}`;
    const group = groups.get(key) ?? {
      key,
      examName: item.examName ?? '其他考试',
      subjectName: item.subjectName ?? '其他科目',
      items: [],
      knowledgePoints: [],
    };
    group.items.push(item);
    const pointKey = item.knowledgePointKey ?? 'UNCLASSIFIED';
    let point = group.knowledgePoints.find(entry => entry.key === pointKey);
    if (!point) {
      point = {
        key: pointKey,
        label: item.knowledgePointName || '未分类考点',
        items: [],
      };
      group.knowledgePoints.push(point);
    }
    point.items.push(item);
    groups.set(key, group);
  }
  return Array.from(groups.values());
}

function toApiFilters(filters: UiFilters): WrongQuestionFilters {
  return {
    examCode: filters.examCode || undefined,
    subjectCode: filters.subjectCode || undefined,
    knowledgePointKey: filters.knowledgePointKey || undefined,
    questionType: filters.questionType || undefined,
    status: filters.status || undefined,
    keyword: filters.keyword.trim() || undefined,
  };
}

function hasActiveFilters(filters: UiFilters) {
  return Boolean(
    filters.examCode || filters.subjectCode || filters.knowledgePointKey
    || filters.questionType || filters.keyword || filters.status !== 'PENDING',
  );
}

function countStatus(data: WrongQuestionPage | null, status: WrongQuestionStatus) {
  return data?.statusCounts.find(item => item.key === status)?.count ?? 0;
}

function statusToneOf(status: WrongQuestionStatus) {
  if (status === 'MASTERED') {
    return 'bg-emerald-50 dark:bg-emerald-900/30 text-emerald-600 dark:text-emerald-300';
  }
  if (status === 'ARCHIVED') {
    return 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400';
  }
  return 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-300';
}

function formatTime(value: string) {
  return value.replace('T', ' ').slice(0, 16);
}

function typeLabel(type: string) {
  const labels: Record<string, string> = {
    SINGLE_CHOICE: '单选题',
    MULTI_CHOICE: '多选题',
    TRUE_FALSE: '判断题',
    FILL_BLANK: '填空题',
    SHORT_ANSWER: '简答题',
    READING: '阅读题',
    WRITING: '写作题',
    CASE_ANALYSIS: '案例分析',
    OTHER: '其他',
  };
  return labels[type] ?? type;
}
