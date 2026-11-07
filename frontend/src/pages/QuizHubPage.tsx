import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  BookOpen,
  Clock,
  Copy,
  Loader2,
  NotebookPen,
  Pencil,
  Plus,
  RefreshCw,
  Sparkles,
  Target,
  Trash2,
} from 'lucide-react';
import quizApi, {
  type QuizCatalog,
  type QuizCatalogSubject,
  type QuizSessionMeta,
} from '../api/quiz';
import { getErrorMessage } from '../api/request';
import { useAuth } from '../context/AuthContext';
import SubjectCreateModal from '../components/SubjectCreateModal';

const DIFFICULTIES = [
  { value: 'junior', label: '基础', hint: '直接考查基本概念，一步即可作答' },
  { value: 'mid', label: '中等', hint: '需要两步以上推理，或对概念做简单变形' },
  { value: 'senior', label: '进阶', hint: '跨知识点综合，需要严谨推理' },
];

const QUESTION_COUNTS = [5, 8, 10, 12];

const TYPE_PRESETS = [
  { value: 'auto', label: '自动配比', types: [] as string[] },
  {
    value: 'objective',
    label: '客观题为主',
    types: ['SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE', 'FILL_BLANK'],
  },
  {
    value: 'subjective',
    label: '主观题为主',
    types: ['SHORT_ANSWER', 'WRITING', 'CASE_ANALYSIS'],
  },
];

const TYPE_LABELS: Record<string, string> = {
  SINGLE_CHOICE: '单选',
  MULTI_CHOICE: '多选',
  TRUE_FALSE: '判断',
  FILL_BLANK: '填空',
  SHORT_ANSWER: '简答',
  READING: '阅读',
  WRITING: '写作',
  CASE_ANALYSIS: '案例分析',
  OTHER: '其他',
};

export default function QuizHubPage() {
  const navigate = useNavigate();
  const { user } = useAuth();

  const [catalog, setCatalog] = useState<QuizCatalog | null>(null);
  const [history, setHistory] = useState<QuizSessionMeta[]>([]);
  const [wrongCount, setWrongCount] = useState(0);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [deleting, setDeleting] = useState<string | null>(null);
  const [error, setError] = useState('');

  const [selectedCode, setSelectedCode] = useState('');
  const [difficulty, setDifficulty] = useState('mid');
  const [questionCount, setQuestionCount] = useState(8);
  const [categories, setCategories] = useState<string[]>([]);
  const [typePreset, setTypePreset] = useState('auto');

  const [modalOpen, setModalOpen] = useState(false);
  const [modalInitial, setModalInitial] = useState<QuizCatalogSubject | null>(null);

  const allSubjects = useMemo(
    () => catalog?.exams.flatMap(exam => exam.subjects) ?? [],
    [catalog],
  );

  const selectedSubject = useMemo(
    () => allSubjects.find(subject => subject.code === selectedCode) ?? null,
    [allSubjects, selectedCode],
  );

  const mainExams = useMemo(
    () => (catalog?.exams ?? []).filter(exam => !exam.customBucket && exam.code !== 'general'),
    [catalog],
  );

  const generalExam = useMemo(
    () => catalog?.exams.find(exam => exam.code === 'general') ?? null,
    [catalog],
  );

  const mySubjects = useMemo(
    () => allSubjects.filter(subject => subject.custom),
    [allSubjects],
  );

  const load = async () => {
    setLoading(true);
    setError('');
    try {
      const [catalogData, historyList, wrongList] = await Promise.all([
        quizApi.catalog(),
        quizApi.history(),
        quizApi.wrongQuestions({ status: 'PENDING' }, 0, 1),
      ]);
      setCatalog(catalogData);
      setHistory(historyList);
      setWrongCount(wrongList.total);
      setSelectedCode(current => {
        if (current) return current;
        const first = catalogData.exams.flatMap(exam => exam.subjects)[0];
        return first ? first.code : '';
      });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const selectSubject = (subject: QuizCatalogSubject) => {
    setSelectedCode(subject.code);
    setCategories([]);
  };

  const toggleCategory = (key: string) => {
    setCategories(prev => (prev.includes(key) ? prev.filter(item => item !== key) : [...prev, key]));
  };

  const handleStart = async () => {
    if (!selectedCode) {
      setError('请先选择科目');
      return;
    }
    setCreating(true);
    setError('');
    try {
      const types = TYPE_PRESETS.find(preset => preset.value === typePreset)?.types ?? [];
      const session = await quizApi.createQuiz({
        skillId: selectedCode,
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

  const handleDeleteSubject = async (subject: QuizCatalogSubject) => {
    if (!window.confirm(`确定删除「${subject.name}」吗？历史测验不受影响。`)) return;
    setDeleting(subject.code);
    setError('');
    try {
      await quizApi.deleteSubject(subject.code);
      if (selectedCode === subject.code) {
        setSelectedCode('');
      }
      await load();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setDeleting(null);
    }
  };

  const openCreate = () => {
    setModalInitial(null);
    setModalOpen(true);
  };

  const openClone = (subject: QuizCatalogSubject) => {
    setModalInitial(subject);
    setModalOpen(true);
  };

  const openEdit = (subject: QuizCatalogSubject) => {
    setModalInitial(subject);
    setModalOpen(true);
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
            选考试科目与考点，AI 组一份卷子，整卷作答后一次交卷批改
          </p>
        </div>
        <button
          onClick={openCreate}
          className="ml-auto flex items-center gap-2 px-4 py-2 rounded-xl border border-primary-200 dark:border-primary-800 text-sm text-primary-600 dark:text-primary-300 hover:bg-primary-50 dark:hover:bg-primary-900/30 transition"
        >
          <Plus className="w-4 h-4" />
          新建科目
        </button>
        <button
          onClick={() => navigate('/wrong-questions')}
          className="flex items-center gap-2 px-4 py-2 rounded-xl border border-slate-200 dark:border-slate-600 text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 transition"
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
      <section className="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 p-6 space-y-6">
        <div className="space-y-5">
          {mainExams.map(exam => (
            <div key={exam.code}>
              <div className="flex items-center gap-2 mb-2">
                {exam.display?.icon && <span className="text-base">{exam.display.icon}</span>}
                <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{exam.name}</p>
              </div>
              <SubjectGrid
                subjects={exam.subjects}
                selectedCode={selectedCode}
                deleting={deleting}
                onSelect={selectSubject}
                onClone={openClone}
              />
            </div>
          ))}

          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2">
                <span className="text-base">✨</span>
                <p className="text-sm font-medium text-slate-700 dark:text-slate-200">我的科目</p>
              </div>
              <button
                onClick={openCreate}
                className="flex items-center gap-1 text-xs text-primary-600 dark:text-primary-300"
              >
                <Plus className="w-3.5 h-3.5" /> 新建
              </button>
            </div>
            {mySubjects.length > 0 ? (
              <SubjectGrid
                subjects={mySubjects}
                selectedCode={selectedCode}
                deleting={deleting}
                onSelect={selectSubject}
                onClone={openClone}
                onEdit={openEdit}
                onDelete={handleDeleteSubject}
              />
            ) : (
              <p className="text-xs text-slate-400">
                还没有自定义科目，可点“新建”或复制内置科目创建
              </p>
            )}
          </div>

          {generalExam && generalExam.subjects.length > 0 && (
            <details className="group">
              <summary className="cursor-pointer list-none flex items-center gap-2 text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200">
                <span className="text-base">📚</span>
                中小学 / 学科练习（旧语数英）
                <span className="text-xs text-slate-400 group-open:hidden">展开</span>
              </summary>
              <div className="mt-2">
                <SubjectGrid
                  subjects={generalExam.subjects}
                  selectedCode={selectedCode}
                  deleting={deleting}
                  onSelect={selectSubject}
                  onClone={openClone}
                />
              </div>
            </details>
          )}
        </div>

        {selectedSubject && (
          <div className="border-t border-slate-100 dark:border-slate-700 pt-5 space-y-5">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <p className="text-sm font-semibold text-slate-800 dark:text-white">
                {selectedSubject.name}
              </p>
              {selectedSubject.suggestedMinutes != null && (
                <span className="flex items-center gap-1 text-xs text-slate-400">
                  <Clock className="w-3.5 h-3.5" />
                  建议 {selectedSubject.suggestedMinutes} 分钟
                </span>
              )}
              {selectedSubject.questionTypes.map(type => (
                <span
                  key={type.type}
                  className="px-2 py-0.5 rounded-md bg-slate-100 dark:bg-slate-700 text-xs text-slate-500 dark:text-slate-300"
                >
                  {TYPE_LABELS[type.type] ?? type.type} {type.defaultScore} 分
                </span>
              ))}
            </div>

            {selectedSubject.knowledgePoints.length > 0 && (
              <div>
                <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">
                  考点
                  <span className="ml-2 text-xs font-normal text-slate-400">不选则覆盖全科</span>
                </p>
                <div className="flex flex-wrap gap-2">
                  {selectedSubject.knowledgePoints.map(point => (
                    <button
                      key={point.code}
                      onClick={() => toggleCategory(point.code)}
                      className={`px-3 py-1.5 rounded-lg border text-xs transition ${
                        categories.includes(point.code)
                          ? 'border-indigo-400 bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600 dark:text-indigo-300'
                          : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-indigo-300'
                      }`}
                    >
                      {point.label}
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
              disabled={creating || !selectedCode}
              className="w-full py-3 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white font-medium disabled:opacity-50 flex items-center justify-center gap-2"
            >
              {creating ? <Loader2 className="w-4 h-4 animate-spin" /> : <Target className="w-4 h-4" />}
              {creating ? '正在组卷，请稍候…' : '开始模拟测验'}
            </button>
            <p className="text-xs text-slate-400 dark:text-slate-500 text-center">
              组卷需要调用模型，通常 10~30 秒
              {user?.displayName ? ` · ${user.displayName}` : ''}
            </p>
          </div>
        )}
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

      <SubjectCreateModal
        open={modalOpen}
        exams={catalog?.exams ?? []}
        initial={modalInitial}
        onClose={() => setModalOpen(false)}
        onSaved={() => {
          setModalOpen(false);
          void load();
        }}
      />
    </div>
  );
}

function SubjectGrid({
  subjects,
  selectedCode,
  deleting,
  onSelect,
  onClone,
  onEdit,
  onDelete,
}: {
  subjects: QuizCatalogSubject[];
  selectedCode: string;
  deleting: string | null;
  onSelect: (subject: QuizCatalogSubject) => void;
  onClone: (subject: QuizCatalogSubject) => void;
  onEdit?: (subject: QuizCatalogSubject) => void;
  onDelete?: (subject: QuizCatalogSubject) => void;
}) {
  return (
    <div className="grid gap-2 sm:grid-cols-2">
      {subjects.map(subject => {
        const selected = subject.code === selectedCode;
        return (
          <div
            key={subject.code}
            className={`flex items-center gap-3 rounded-xl border p-3 cursor-pointer transition ${
              selected
                ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/20'
                : 'border-slate-200 dark:border-slate-600 hover:border-primary-300'
            }`}
            onClick={() => onSelect(subject)}
          >
            <BookOpen
              className={`w-4 h-4 shrink-0 ${
                selected ? 'text-primary-500' : 'text-slate-400'
              }`}
            />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1.5">
                <p className="truncate text-sm font-medium text-slate-800 dark:text-slate-100">
                  {subject.name}
                </p>
                {subject.custom && (
                  <span className="shrink-0 px-1.5 py-0.5 rounded bg-fuchsia-100 dark:bg-fuchsia-900/40 text-fuchsia-600 dark:text-fuchsia-300 text-[10px]">
                    我的
                  </span>
                )}
              </div>
              <p className="truncate text-xs text-slate-400">
                {(subject.questionTypes ?? [])
                  .map(type => TYPE_LABELS[type.type] ?? type.type)
                  .join(' / ')}
              </p>
            </div>
            {!subject.custom && (
              <button
                onClick={event => {
                  event.stopPropagation();
                  onClone(subject);
                }}
                title="复制为我的科目"
                className="shrink-0 p-1.5 rounded-lg text-slate-400 hover:text-primary-500 hover:bg-slate-100 dark:hover:bg-slate-700"
              >
                <Copy className="w-4 h-4" />
              </button>
            )}
            {subject.custom && onEdit && (
              <button
                onClick={event => {
                  event.stopPropagation();
                  onEdit(subject);
                }}
                title="编辑"
                className="shrink-0 p-1.5 rounded-lg text-slate-400 hover:text-primary-500 hover:bg-slate-100 dark:hover:bg-slate-700"
              >
                <Pencil className="w-4 h-4" />
              </button>
            )}
            {subject.custom && onDelete && (
              <button
                onClick={event => {
                  event.stopPropagation();
                  void onDelete(subject);
                }}
                disabled={deleting === subject.code}
                title="删除"
                className="shrink-0 p-1.5 rounded-lg text-slate-400 hover:text-rose-500 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-50"
              >
                {deleting === subject.code ? (
                  <Loader2 className="w-4 h-4 animate-spin" />
                ) : (
                  <Trash2 className="w-4 h-4" />
                )}
              </button>
            )}
          </div>
        );
      })}
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
