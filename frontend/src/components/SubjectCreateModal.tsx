import { useEffect, useState } from 'react';
import { Loader2, Plus, Trash2, X } from 'lucide-react';
import quizApi, {
  type QuizCatalogSubject,
  type QuizExam,
  type QuizQuestionType,
} from '../api/quiz';
import { getErrorMessage } from '../api/request';

const ALL_QUESTION_TYPES: { type: string; label: string }[] = [
  { type: 'SINGLE_CHOICE', label: '单选' },
  { type: 'MULTI_CHOICE', label: '多选' },
  { type: 'TRUE_FALSE', label: '判断' },
  { type: 'FILL_BLANK', label: '填空' },
  { type: 'SHORT_ANSWER', label: '简答' },
  { type: 'READING', label: '阅读' },
  { type: 'WRITING', label: '写作' },
  { type: 'CASE_ANALYSIS', label: '案例分析' },
  { type: 'OTHER', label: '其他' },
];

interface SubjectCreateModalProps {
  open: boolean;
  exams: QuizExam[];
  /** 复制内置科目或编辑自定义科目时预填 */
  initial?: QuizCatalogSubject | null;
  onClose: () => void;
  onSaved: (subject: QuizCatalogSubject) => void;
}

interface DraftType extends QuizQuestionType {}

export default function SubjectCreateModal({
  open,
  exams,
  initial,
  onClose,
  onSaved,
}: SubjectCreateModalProps) {
  const editing = Boolean(initial?.custom);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [examCode, setExamCode] = useState('');
  const [points, setPoints] = useState<string[]>([]);
  const [types, setTypes] = useState<DraftType[]>([]);
  const [suggestedMinutes, setSuggestedMinutes] = useState('');
  const [outlineText, setOutlineText] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) return;
    setName(initial?.name ?? '');
    setDescription(initial?.description ?? '');
    setExamCode(initial?.examCode ?? exams[0]?.code ?? '');
    setPoints((initial?.knowledgePoints ?? []).map(point => point.label));
    setTypes((initial?.questionTypes ?? []).map(type => ({ ...type })));
    setSuggestedMinutes(initial?.suggestedMinutes != null ? String(initial.suggestedMinutes) : '');
    setOutlineText(initial?.outlineText ?? '');
    setError('');
  }, [open, initial, exams]);

  if (!open) return null;

  const addPoint = () => setPoints(prev => [...prev, '']);
  const removePoint = (index: number) => setPoints(prev => prev.filter((_, i) => i !== index));

  const toggleType = (type: string) => {
    setTypes(prev => {
      if (prev.some(item => item.type === type)) {
        return prev.filter(item => item.type !== type);
      }
      const defaults: Record<string, [number, number]> = {
        SINGLE_CHOICE: [2, 3],
        MULTI_CHOICE: [2, 2],
        TRUE_FALSE: [1, 2],
        FILL_BLANK: [2, 2],
        SHORT_ANSWER: [10, 3],
        READING: [5, 2],
        WRITING: [30, 1],
        CASE_ANALYSIS: [14, 2],
        OTHER: [5, 1],
      };
      const [defaultScore, weight] = defaults[type] ?? [5, 1];
      return [...prev, { type, defaultScore, weight }];
    });
  };

  const updateType = (index: number, patch: Partial<DraftType>) => {
    setTypes(prev => prev.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  };

  const handleSave = async () => {
    const trimmedName = name.trim();
    const trimmedPoints = points.map(point => point.trim()).filter(point => point.length > 0);
    if (!trimmedName) {
      setError('请填写科目名称');
      return;
    }
    if (!examCode) {
      setError('请选择所属考试');
      return;
    }
    if (trimmedPoints.length === 0) {
      setError('请至少添加一个考点');
      return;
    }
    if (types.length === 0) {
      setError('请至少选择一种题型');
      return;
    }
    const parsedMinutes = suggestedMinutes.trim() === '' ? undefined : Number(suggestedMinutes);
    if (parsedMinutes != null && (!Number.isFinite(parsedMinutes) || parsedMinutes <= 0)) {
      setError('建议时长必须是正数');
      return;
    }
    const payload = {
      name: trimmedName,
      description: description.trim() || undefined,
      examCode,
      knowledgePoints: trimmedPoints.map(label => ({ label })),
      questionTypes: types.map(type => ({
        type: type.type,
        defaultScore: Number(type.defaultScore),
        weight: Number(type.weight),
      })),
      suggestedMinutes: parsedMinutes,
      outlineText: outlineText.trim() || undefined,
    };
    setSaving(true);
    setError('');
    try {
      const saved = editing && initial
        ? await quizApi.updateSubject(initial.code, payload)
        : await quizApi.createSubject(payload);
      onSaved(saved);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-2xl bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 shadow-xl">
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-slate-100 dark:border-slate-700 bg-white dark:bg-slate-800 px-5 py-4">
          <h2 className="text-base font-semibold text-slate-800 dark:text-white">
            {editing ? '编辑科目' : initial ? '复制为我的科目' : '新建科目'}
          </h2>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="px-5 py-4 space-y-4">
          {error && (
            <div className="px-3 py-2 rounded-lg bg-rose-50 dark:bg-rose-900/30 text-rose-600 dark:text-rose-300 text-sm">
              {error}
            </div>
          )}

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">
                科目名称
              </label>
              <input
                value={name}
                onChange={event => setName(event.target.value)}
                placeholder="如：软件工程基础"
                className="w-full rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">
                所属考试
              </label>
              <select
                value={examCode}
                onChange={event => setExamCode(event.target.value)}
                className="w-full rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-primary-500/30"
              >
                {exams.map(exam => (
                  <option key={exam.code} value={exam.code}>
                    {exam.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">
              科目说明
            </label>
            <textarea
              value={description}
              onChange={event => setDescription(event.target.value)}
              rows={2}
              placeholder="一句话说明这个科目考什么（可选）"
              className="w-full rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-primary-500/30"
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="text-sm font-medium text-slate-700 dark:text-slate-200">考点</label>
              <button
                onClick={addPoint}
                className="flex items-center gap-1 text-xs text-primary-600 dark:text-primary-300"
              >
                <Plus className="w-3.5 h-3.5" /> 添加考点
              </button>
            </div>
            <div className="space-y-2">
              {points.map((point, index) => (
                <div key={index} className="flex items-center gap-2">
                  <input
                    value={point}
                    onChange={event =>
                      setPoints(prev => prev.map((item, i) => (i === index ? event.target.value : item)))
                    }
                    placeholder={`考点 ${index + 1}`}
                    className="flex-1 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-primary-500/30"
                  />
                  <button
                    onClick={() => removePoint(index)}
                    className="p-2 rounded-lg text-slate-400 hover:text-rose-500"
                    title="删除考点"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
              ))}
              {points.length === 0 && (
                <p className="text-xs text-slate-400">还没有考点，点击右上角“添加考点”</p>
              )}
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">
              题型（勾选并设置默认分与权重）
            </label>
            <div className="flex flex-wrap gap-2 mb-3">
              {ALL_QUESTION_TYPES.map(item => {
                const active = types.some(type => type.type === item.type);
                return (
                  <button
                    key={item.type}
                    onClick={() => toggleType(item.type)}
                    className={`px-3 py-1.5 rounded-lg border text-xs transition ${
                      active
                        ? 'border-primary-400 bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-300'
                        : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300'
                    }`}
                  >
                    {item.label}
                  </button>
                );
              })}
            </div>
            {types.length > 0 && (
              <div className="space-y-2">
                {types.map((type, index) => (
                  <div key={type.type} className="flex items-center gap-2">
                    <span className="w-16 text-sm text-slate-600 dark:text-slate-300">
                      {ALL_QUESTION_TYPES.find(item => item.type === type.type)?.label ?? type.type}
                    </span>
                    <input
                      type="number"
                      min={1}
                      value={type.defaultScore}
                      onChange={event =>
                        updateType(index, { defaultScore: Number(event.target.value) })
                      }
                      className="w-24 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-2 py-1.5 text-sm text-slate-800 dark:text-slate-100"
                      title="每题默认分"
                    />
                    <span className="text-xs text-slate-400">分</span>
                    <input
                      type="number"
                      min={1}
                      value={type.weight}
                      onChange={event => updateType(index, { weight: Number(event.target.value) })}
                      className="w-24 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-2 py-1.5 text-sm text-slate-800 dark:text-slate-100"
                      title="题量权重"
                    />
                    <span className="text-xs text-slate-400">权重</span>
                    <button
                      onClick={() => setTypes(prev => prev.filter(item => item.type !== type.type))}
                      className="ml-auto p-1.5 rounded-lg text-slate-400 hover:text-rose-500"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">
                建议时长（分钟，仅展示）
              </label>
              <input
                type="number"
                min={1}
                value={suggestedMinutes}
                onChange={event => setSuggestedMinutes(event.target.value)}
                placeholder="如 60"
                className="w-full rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-primary-500/30"
              />
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">
              整科大纲（可选，会注入出题）
            </label>
            <textarea
              value={outlineText}
              onChange={event => setOutlineText(event.target.value)}
              rows={4}
              placeholder="粘贴考试大纲或你希望重点覆盖的内容，出题时会作为命题依据"
              className="w-full rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-primary-500/30"
            />
          </div>
        </div>

        <div className="sticky bottom-0 flex justify-end gap-2 border-t border-slate-100 dark:border-slate-700 bg-white dark:bg-slate-800 px-5 py-4">
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-xl border border-slate-200 dark:border-slate-600 text-sm text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700"
          >
            取消
          </button>
          <button
            onClick={() => void handleSave()}
            disabled={saving}
            className="flex items-center gap-2 px-4 py-2 rounded-xl bg-gradient-to-r from-primary-500 to-indigo-500 text-white text-sm font-medium disabled:opacity-50"
          >
            {saving && <Loader2 className="w-4 h-4 animate-spin" />}
            {editing ? '保存修改' : '创建科目'}
          </button>
        </div>
      </div>
    </div>
  );
}
