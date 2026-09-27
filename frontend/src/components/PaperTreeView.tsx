import { useMemo, useState } from 'react';
import { ChevronDown, ChevronRight, FileText } from 'lucide-react';
import type { PaperNode, PaperTree } from '../types/interview';
import QuestionAnswerInput, { EMPTY_ANSWER, type AnswerValue } from './QuestionAnswerInput';

interface PaperTreeViewProps {
  tree: PaperTree;
  answers: Record<number, AnswerValue>;
  disabled?: boolean;
  onAnswerChange: (questionIndex: number, value: AnswerValue) => void;
}

/**
 * 按层级渲染试卷：SECTION（大题/小节）→ MATERIAL（材料）→ QUESTION（题目）。
 * 一级默认展开，二级及更深默认折叠；材料默认展开、可单独折叠。
 */
export default function PaperTreeView({
  tree,
  answers,
  disabled,
  onAnswerChange,
}: PaperTreeViewProps) {
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>(() =>
    collectDefaultCollapsed(tree.root)
  );
  const [materialsOpen, setMaterialsOpen] = useState<Record<string, boolean>>({});

  const toggle = (id: string) =>
    setCollapsed(prev => ({ ...prev, [id]: !prev[id] }));
  const toggleMaterial = (id: string) =>
    setMaterialsOpen(prev => ({ ...prev, [id]: prev[id] === false }));

  return (
    <div className="space-y-4">
      {(tree.root.children ?? []).map(node => (
        <SectionBlock
          key={node.id}
          node={node}
          answers={answers}
          disabled={disabled}
          collapsed={collapsed}
          materialsOpen={materialsOpen}
          onToggle={toggle}
          onToggleMaterial={toggleMaterial}
          onAnswerChange={onAnswerChange}
        />
      ))}
    </div>
  );
}

function collectDefaultCollapsed(root: PaperNode): Record<string, boolean> {
  const result: Record<string, boolean> = {};
  const walk = (node: PaperNode, depth: number) => {
    if (node.type === 'SECTION' && depth >= 2) {
      result[node.id] = true;
    }
    (node.children ?? []).forEach(child => walk(child, depth + 1));
  };
  (root.children ?? []).forEach(child => walk(child, 1));
  return result;
}

interface BlockProps {
  node: PaperNode;
  answers: Record<number, AnswerValue>;
  disabled?: boolean;
  collapsed: Record<string, boolean>;
  materialsOpen: Record<string, boolean>;
  onToggle: (id: string) => void;
  onToggleMaterial: (id: string) => void;
  onAnswerChange: (questionIndex: number, value: AnswerValue) => void;
}

function SectionBlock({
  node,
  answers,
  disabled,
  collapsed,
  materialsOpen,
  onToggle,
  onToggleMaterial,
  onAnswerChange,
}: BlockProps) {
  const stats = useMemo(() => summarize(node), [node]);
  const isTop = node.depth <= 1;
  const isCollapsed = Boolean(collapsed[node.id]);

  return (
    <section
      className={
        isTop
          ? 'bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 border-l-4 border-l-primary-500 overflow-hidden'
          : 'border-l-2 border-slate-200 dark:border-slate-700 pl-4'
      }
    >
      <header
        className={`flex flex-wrap gap-3 items-center justify-between ${
          isTop ? 'px-5 py-3 bg-gradient-to-r from-primary-50 to-transparent dark:from-primary-900/20' : 'py-1'
        }`}
      >
        <div className="flex items-start gap-2">
          <button
            type="button"
            onClick={() => onToggle(node.id)}
            className="mt-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
            aria-label={isCollapsed ? '展开' : '收起'}
            aria-expanded={!isCollapsed}
          >
            {isCollapsed ? <ChevronRight className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
          </button>
          <div>
            <h3 className={isTop ? 'font-semibold text-slate-900 dark:text-white' : 'font-medium text-slate-800 dark:text-slate-200'}>
              {node.title}
            </h3>
            <p className="text-xs text-slate-500 dark:text-slate-400">
              {node.subtitle ? `${node.subtitle} · ` : ''}
              本${isTop ? '大题' : '小节'}共 {stats.questionCount} 小题
              {stats.fullScore > 0 ? `，${stats.fullScore} 分` : ''}
            </p>
          </div>
        </div>
      </header>

      {!isCollapsed && (
        <div className={isTop ? 'px-5 py-4 space-y-4' : 'py-3 space-y-3'}>
          {(node.children ?? []).map(child => {
            if (child.type === 'SECTION') {
              return (
                <SectionBlock
                  key={child.id}
                  node={child}
                  answers={answers}
                  disabled={disabled}
                  collapsed={collapsed}
                  materialsOpen={materialsOpen}
                  onToggle={onToggle}
                  onToggleMaterial={onToggleMaterial}
                  onAnswerChange={onAnswerChange}
                />
              );
            }
            if (child.type === 'MATERIAL') {
              const open = materialsOpen[child.id] !== false;
              return (
                <div
                  key={child.id}
                  className="rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/50 px-4 py-3"
                >
                  <div className="flex items-center justify-between gap-3 mb-1">
                    <span className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
                      <FileText className="w-3.5 h-3.5" />
                      {child.title || '材料'}
                    </span>
                    <button
                      type="button"
                      onClick={() => onToggleMaterial(child.id)}
                      className="text-xs text-slate-500 hover:text-slate-700 dark:hover:text-slate-300"
                    >
                      {open ? '收起材料' : '展开材料'}
                    </button>
                  </div>
                  {open && (
                    <p className="text-sm text-slate-700 dark:text-slate-300 whitespace-pre-wrap leading-relaxed">
                      {child.content}
                    </p>
                  )}
                </div>
              );
            }
            if (child.type === 'INSTRUCTION') {
              return (
                <p
                  key={child.id}
                  className="text-xs text-slate-500 dark:text-slate-400 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-lg px-3 py-2"
                >
                  {child.content}
                </p>
              );
            }
            return (
              <QuestionBlock
                key={child.id}
                node={child}
                value={answers[child.questionIndex ?? -1] ?? EMPTY_ANSWER}
                disabled={disabled}
                onAnswerChange={onAnswerChange}
              />
            );
          })}
        </div>
      )}
    </section>
  );
}

function QuestionBlock({
  node,
  value,
  disabled,
  onAnswerChange,
}: {
  node: PaperNode;
  value: AnswerValue;
  disabled?: boolean;
  onAnswerChange: (questionIndex: number, value: AnswerValue) => void;
}) {
  const question = node.question;
  const questionIndex = node.questionIndex ?? question?.questionIndex ?? -1;
  if (!question || questionIndex < 0) {
    return null;
  }
  return (
    <article
      data-question-index={questionIndex}
      className="rounded-xl border border-slate-200 dark:border-slate-700 p-4"
    >
      <div className="flex flex-wrap items-center gap-2 mb-2">
        <span className="px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 text-xs font-medium">
          第 {questionIndex + 1} 题
        </span>
        {question.category && (
          <span className="px-2 py-0.5 rounded bg-indigo-50 dark:bg-indigo-900/40 text-indigo-600 dark:text-indigo-300 text-xs">
            {question.category}
          </span>
        )}
        {question.questionType && (
          <span className="px-2 py-0.5 rounded border border-primary-200 dark:border-primary-800 text-primary-600 dark:text-primary-400 text-xs">
            {typeLabel(question.questionType)}
          </span>
        )}
        {question.fullScore ? (
          <span className="px-2 py-0.5 rounded border border-amber-200 dark:border-amber-800 text-amber-600 dark:text-amber-400 text-xs">
            {question.fullScore} 分
          </span>
        ) : null}
      </div>
      <p className="text-slate-800 dark:text-slate-100 whitespace-pre-wrap mb-3">{question.question}</p>
      <QuestionAnswerInput
        question={question}
        value={value}
        disabled={disabled}
        onChange={next => onAnswerChange(questionIndex, next)}
      />
    </article>
  );
}

function typeLabel(questionType: string): string {
  switch (questionType) {
    case 'SINGLE_CHOICE':
      return '单选题';
    case 'MULTI_CHOICE':
      return '多选题';
    case 'FILL_BLANK':
      return '填空题';
    case 'SHORT_ANSWER':
      return '简答题';
    case 'READING':
      return '阅读题';
    case 'WRITING':
      return '写作题';
    default:
      return '题目';
  }
}

function summarize(node: PaperNode): { questionCount: number; fullScore: number } {
  let questionCount = 0;
  let fullScore = 0;
  const walk = (current: PaperNode) => {
    if (current.type === 'QUESTION') {
      questionCount += 1;
      fullScore += current.question?.fullScore ?? 0;
    }
    (current.children ?? []).forEach(walk);
  };
  (node.children ?? []).forEach(walk);
  return { questionCount, fullScore };
}
