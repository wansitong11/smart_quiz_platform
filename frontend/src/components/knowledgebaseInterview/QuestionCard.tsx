import { useState } from 'react';
import {
  Archive,
  Check,
  ChevronDown,
  Pencil,
  Trash2,
} from 'lucide-react';
import type {
  KnowledgeBaseQuestion,
  KnowledgeBaseQuestionStatus,
} from '../../api/knowledgebase';
import {
  getDifficultyLabel,
  getStatusLabel,
} from '../../constants/knowledgebaseInterview';

/** 题型中文名（与后端 InterviewQuestionDTO.questionType 对齐） */
const QUESTION_TYPE_LABELS: Record<string, string> = {
  SINGLE_CHOICE: '单选题',
  MULTI_CHOICE: '多选题',
  FILL_BLANK: '填空题',
  SHORT_ANSWER: '简答题',
  READING: '阅读题',
  WRITING: '写作题',
  OTHER: '其他',
};

interface QuestionCardProps {
  question: KnowledgeBaseQuestion;
  selected?: boolean;
  targetFollowUpCount?: number | null;
  onSelect?: (id: number) => void;
  /** 下面三个回调不传即表示"只读"（共享资料对普通用户只读），对应按钮不渲染 */
  onEdit?: (question: KnowledgeBaseQuestion) => void;
  onUpdateStatus?: (questionId: number, status: KnowledgeBaseQuestionStatus) => void;
  onDelete?: (question: KnowledgeBaseQuestion) => void;
}

export default function QuestionCard({
  question,
  selected = false,
  onSelect,
  onEdit,
  onUpdateStatus,
  onDelete,
}: QuestionCardProps) {
  const [expanded, setExpanded] = useState(false);
  const options = question.options ?? [];
  const hasDetail = Boolean(
    question.referenceAnswer
      || question.keyPoints.length > 0
      || question.scoringRubric
      || question.analysis
  );

  return (
    <div
      className={`bg-white dark:bg-slate-800 border rounded-xl p-4 transition-colors ${
        selected
          ? 'border-primary-300 dark:border-primary-700 bg-primary-50/40 dark:bg-primary-900/10'
          : 'border-slate-100 dark:border-slate-700'
      }`}
    >
      <div className="flex items-start gap-3">
        {onSelect && (
          <input
            type="checkbox"
            checked={selected}
            onChange={() => onSelect(question.id)}
            className="mt-1 w-4 h-4 rounded border-slate-300 text-primary-500 focus:ring-primary-500/30 shrink-0"
          />
        )}
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2 mb-2">
            <Badge>{getStatusLabel(question.status)}</Badge>
            {question.category && <Badge>{question.category}</Badge>}
            <Badge>{getDifficultyLabel(question.difficulty)}</Badge>
            {question.questionType && (
              <Badge>{QUESTION_TYPE_LABELS[question.questionType] ?? question.questionType}</Badge>
            )}
            {question.fullScore ? <Badge>{`${question.fullScore} 分`}</Badge> : null}
          </div>
          <button
            type="button"
            onClick={() => hasDetail && setExpanded(prev => !prev)}
            className={`block w-full text-left ${hasDetail ? 'cursor-pointer' : 'cursor-default'}`}
          >
            <h3 className="font-semibold text-slate-900 dark:text-white leading-relaxed">
              {question.question}
            </h3>
          </button>
          {/* 选项直接展示，避免"只有题干、看着不像完整题目"（答案仍只在展开区） */}
          {options.length >= 2 && (
            <ul className="mt-3 space-y-1.5">
              {options.map(option => (
                <li
                  key={option.key}
                  className="flex items-start gap-2 text-sm text-slate-700 dark:text-slate-200"
                >
                  <span className="min-w-[18px] font-medium text-slate-500 dark:text-slate-400">
                    {option.key}
                  </span>
                  <span className="whitespace-pre-wrap">{option.text}</span>
                </li>
              ))}
            </ul>
          )}
          {hasDetail && (
            <button
              type="button"
              onClick={() => setExpanded(prev => !prev)}
              className="mt-2 inline-flex items-center gap-1 text-xs text-primary-500 hover:text-primary-600"
            >
              <ChevronDown className={`w-3.5 h-3.5 transition-transform ${expanded ? 'rotate-180' : ''}`} />
              {expanded ? '收起详情' : '查看参考答案与解析'}
            </button>
          )}
          {expanded && (
            <div className="mt-3 space-y-3 border-t border-slate-100 dark:border-slate-700 pt-3">
              {question.topicSummary && (
                <DetailSection title="主题摘要" body={question.topicSummary} />
              )}
              {question.referenceAnswer && (
                <DetailSection title="参考答案" body={question.referenceAnswer} />
              )}
              {question.analysis && (
                <DetailSection title="题目解析" body={question.analysis} />
              )}
              {question.keyPoints.length > 0 && (
                <div>
                  <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">评分要点</p>
                  <ul className="space-y-1">
                    {question.keyPoints.map((point, idx) => (
                      <li key={idx} className="text-sm text-slate-700 dark:text-slate-200 flex gap-2">
                        <span className="text-primary-500 shrink-0">•</span>
                        <span>{point}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              {question.scoringRubric && (
                <DetailSection title="评分规则" body={question.scoringRubric} />
              )}
              {question.followUps.length > 0 && (
                <div>
                  <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-2">
                    追问（{question.followUps.length}）
                  </p>
                  <div className="space-y-2">
                    {question.followUps.map((followUp, idx) => (
                      <div
                        key={idx}
                        className="rounded-lg bg-slate-50 dark:bg-slate-900 px-3 py-2"
                      >
                        <p className="text-sm font-medium text-slate-800 dark:text-white">
                          {idx + 1}. {followUp.question}
                        </p>
                        {followUp.referenceAnswer && (
                          <p className="mt-1 text-xs text-slate-500 dark:text-slate-400 whitespace-pre-line">
                            参考答案：{followUp.referenceAnswer}
                          </p>
                        )}
                        {followUp.keyPoints && followUp.keyPoints.length > 0 && (
                          <p className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                            要点：{followUp.keyPoints.join('、')}
                          </p>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
        {(onEdit || onUpdateStatus || onDelete) && (
          <div className="flex items-center gap-1 shrink-0">
            {onEdit && (
              <IconButton title="编辑" onClick={() => onEdit(question)}>
                <Pencil className="w-4 h-4" />
              </IconButton>
            )}
            {onUpdateStatus && question.status !== 'ACTIVE' && (
              <IconButton
                title="启用"
                onClick={() => onUpdateStatus(question.id, 'ACTIVE')}
              >
                <Check className="w-4 h-4" />
              </IconButton>
            )}
            {onUpdateStatus && question.status !== 'ARCHIVED' && (
              <IconButton
                title="归档"
                onClick={() => onUpdateStatus(question.id, 'ARCHIVED')}
              >
                <Archive className="w-4 h-4" />
              </IconButton>
            )}
            {onDelete && (
              <IconButton title="删除" onClick={() => onDelete(question)}>
                <Trash2 className="w-4 h-4" />
              </IconButton>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function Badge({
  children,
  tone = 'default',
}: {
  children: string;
  tone?: 'default' | 'warning';
}) {
  const colorClass = tone === 'warning'
    ? 'bg-amber-50 dark:bg-amber-900/30 text-amber-700 dark:text-amber-300'
    : 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300';
  return (
    <span className={`px-2 py-0.5 rounded text-xs ${colorClass}`}>
      {children}
    </span>
  );
}

function DetailSection({ title, body }: { title: string; body: string }) {
  return (
    <div>
      <p className="text-xs font-semibold text-slate-500 dark:text-slate-400 mb-1">{title}</p>
      <p className="text-sm text-slate-700 dark:text-slate-200 whitespace-pre-line">{body}</p>
    </div>
  );
}

function IconButton({
  title,
  children,
  onClick,
}: {
  title: string;
  children: React.ReactNode;
  onClick: () => void;
}) {
  return (
    <button
      onClick={onClick}
      className="p-2 rounded-lg text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30"
      title={title}
    >
      {children}
    </button>
  );
}
