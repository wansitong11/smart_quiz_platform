import type { InterviewQuestion } from '../types/interview';
import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import MarkupText from './MarkupText';
import { paperRecognitionApi } from '../api/paperRecognition';

/** 结构化作答：选择题记录选中的选项 key，填空与主观题记录文本 */
export interface AnswerValue {
  selectedKeys: string[];
  text: string;
}

export const EMPTY_ANSWER: AnswerValue = { selectedKeys: [], text: '' };

export function isAnswerEmpty(value?: AnswerValue | null): boolean {
  if (!value) return true;
  return value.selectedKeys.length === 0 && !value.text.trim();
}

export function toSubmitAnswer(value?: AnswerValue | null): { selectedKeys: string[]; answer: string } {
  const safe = value ?? EMPTY_ANSWER;
  return {
    selectedKeys: safe.selectedKeys,
    answer: safe.selectedKeys.length > 0 ? safe.selectedKeys.join('') : safe.text.trim(),
  };
}

type RenderKind = 'SINGLE_CHOICE' | 'MULTI_CHOICE' | 'FILL_BLANK' | 'TEXT';

/**
 * 题型驱动的作答控件：单选题点选、多选题多选、填空题输入框、其余为多行文本。
 * 后端未能判定题型（或老数据没有选项）时统一退化为多行文本，保证兼容。
 */
function resolveKind(question: InterviewQuestion): RenderKind {
  const options = question.options ?? [];
  const type = (question.questionType ?? '').toUpperCase();
  if (options.length >= 2 && type === 'SINGLE_CHOICE') return 'SINGLE_CHOICE';
  if (options.length >= 2 && type === 'MULTI_CHOICE') return 'MULTI_CHOICE';
  if (type === 'FILL_BLANK') return 'FILL_BLANK';
  return 'TEXT';
}

interface QuestionAnswerInputProps {
  question: InterviewQuestion;
  value?: AnswerValue;
  disabled?: boolean;
  /** 试卷 ID：图形选项需要它来取资源 */
  paperId?: number;
  onChange: (value: AnswerValue) => void;
}

export default function QuestionAnswerInput({
  question,
  value,
  disabled,
  paperId,
  onChange,
}: QuestionAnswerInputProps) {
  const kind = resolveKind(question);
  const current = value ?? EMPTY_ANSWER;

  if (kind === 'SINGLE_CHOICE' || kind === 'MULTI_CHOICE') {
    const multi = kind === 'MULTI_CHOICE';
    const hasImageOptions = paperId != null
      && (question.options ?? []).some(option => option.assetId);
    if (hasImageOptions) {
      return (
        <div className="space-y-2">
          <div className="grid grid-cols-2 gap-3">
            {(question.options ?? []).map(option => (
              <ImageOptionCard
                key={option.key}
                paperId={paperId}
                optionKey={option.key}
                assetId={option.assetId}
                selected={current.selectedKeys.includes(option.key)}
                multi={multi}
                disabled={disabled}
                onSelect={() => {
                  const selectedKeys = multi
                    ? current.selectedKeys.includes(option.key)
                      ? current.selectedKeys.filter(key => key !== option.key)
                      : [...current.selectedKeys, option.key].sort()
                    : [option.key];
                  onChange({ selectedKeys, text: '' });
                }}
              />
            ))}
          </div>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            {multi ? '多选题：可点选多个图形选项' : '单选题：点击图形选项即可选中'}
          </p>
        </div>
      );
    }
    return (
      <div className="space-y-2">
        <div className="space-y-2">
          {(question.options ?? []).map(option => {
            const selected = current.selectedKeys.includes(option.key);
            return (
              <label
                key={option.key}
                className={`flex items-start gap-3 rounded-xl border px-3 py-2.5 cursor-pointer transition-colors ${
                  selected
                    ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20'
                    : 'border-slate-200 dark:border-slate-600 hover:border-primary-300 dark:hover:border-primary-700'
                }`}
              >
                <input
                  type={multi ? 'checkbox' : 'radio'}
                  name={`question-${question.questionIndex}`}
                  className="mt-1 accent-primary-500"
                  checked={selected}
                  disabled={disabled}
                  onChange={() => {
                    const selectedKeys = multi
                      ? selected
                        ? current.selectedKeys.filter(key => key !== option.key)
                        : [...current.selectedKeys, option.key].sort()
                      : [option.key];
                    onChange({ selectedKeys, text: '' });
                  }}
                />
                <span
                  className={`min-w-[18px] font-medium ${
                    selected ? 'text-primary-600 dark:text-primary-400' : 'text-slate-500 dark:text-slate-400'
                  }`}
                >
                  {option.key}
                </span>
                <MarkupText
                  text={option.text}
                  className="flex-1 whitespace-pre-wrap text-slate-800 dark:text-slate-100"
                />
              </label>
            );
          })}
        </div>
        <p className="text-xs text-slate-500 dark:text-slate-400">
          {multi
            ? `多选题：可点选多个${current.selectedKeys.length > 0 ? `，已选 ${current.selectedKeys.length} 项` : ''}`
            : '单选题：点击选项即可选中'}
        </p>
      </div>
    );
  }

  if (kind === 'FILL_BLANK') {
    return (
      <div className="space-y-1.5">
        <input
          type="text"
          value={current.text}
          disabled={disabled}
          placeholder="在此填写答案"
          aria-label={`第 ${question.questionIndex + 1} 题答案`}
          onChange={event => onChange({ selectedKeys: [], text: event.target.value })}
          className="w-full rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500"
        />
        <p className="text-xs text-slate-500 dark:text-slate-400">
          填空题：答案会与标准答案做规范化比对（忽略空格与标点）
        </p>
      </div>
    );
  }

  const wordCount = current.text.replace(/\s/g, '').length;
  return (
    <div className="space-y-1.5">
      <textarea
        value={current.text}
        disabled={disabled}
        rows={4}
        placeholder="在这里写下你的答案（要点式作答即可，也可以留空跳过）"
        aria-label={`第 ${question.questionIndex + 1} 题答案`}
        onChange={event => onChange({ selectedKeys: [], text: event.target.value })}
        className="w-full rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 p-3 text-sm text-slate-800 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-primary-500/30 focus:border-primary-500"
      />
      <div className="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400">
        <span>主观题：按评分标准踩点给分</span>
        <span>已写 {wordCount} 字</span>
      </div>
    </div>
  );
}

/**
 * 图形选项：按 assetId 取图，点击整张卡片选中。
 */
function ImageOptionCard({
  paperId,
  optionKey,
  assetId,
  selected,
  multi,
  disabled,
  onSelect,
}: {
  paperId: number;
  optionKey: string;
  assetId?: string | null;
  selected: boolean;
  multi: boolean;
  disabled?: boolean;
  onSelect: () => void;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!assetId) return;
    let cancelled = false;
    let objectUrl: string | null = null;
    paperRecognitionApi.fetchAsset(paperId, assetId)
      .then(blob => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [paperId, assetId]);

  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onSelect}
      className={`relative flex min-h-32 flex-col items-center justify-center gap-2 rounded-xl border p-3 transition ${
        selected
          ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/20'
          : 'border-slate-200 hover:border-primary-300 dark:border-slate-600 dark:hover:border-primary-700'
      } disabled:cursor-not-allowed disabled:opacity-60`}
    >
      <span className={`absolute left-2 top-2 flex h-6 w-6 items-center justify-center rounded-full text-xs font-semibold ${
        selected ? 'bg-primary-500 text-white' : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-200'
      }`}>
        {optionKey}
      </span>
      {url ? (
        <img src={url} alt={`选项 ${optionKey}`} className="max-h-40 w-full object-contain" />
      ) : failed ? (
        <span className="text-xs text-slate-400">选项图片加载失败</span>
      ) : (
        <Loader2 className="h-5 w-5 animate-spin text-slate-400" />
      )}
      <span className="text-[11px] text-slate-400">{multi ? '可多选' : '点击选择'}</span>
    </button>
  );
}
