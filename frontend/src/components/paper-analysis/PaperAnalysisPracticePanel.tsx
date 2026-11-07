import type { MutableRefObject } from 'react';
import { motion } from 'framer-motion';
import { Loader2, Send } from 'lucide-react';
import QuestionAnswerInput, {
  EMPTY_ANSWER,
  isAnswerEmpty,
  type AnswerValue,
} from '../QuestionAnswerInput';
import PaperTreeView from '../PaperTreeView';
import { buildQuestionGroups, groupProgress } from '../../utils/paperTree';
import type { InterviewQuestion, PaperTree } from '../../types/interview';

export function PaperAnalysisPracticePanel({
  questions,
  paperTree,
  answers,
  answeredCount,
  totalFullScore,
  paperId,
  submitting,
  questionRefs,
  onAnswerChange,
  onScrollTo,
  onSubmit,
}: {
  questions: InterviewQuestion[];
  paperTree: PaperTree | null;
  answers: Record<number, AnswerValue>;
  answeredCount: number;
  totalFullScore: number;
  paperId?: number;
  submitting: boolean;
  questionRefs: MutableRefObject<Record<number, HTMLDivElement | null>>;
  onAnswerChange: (index: number, value: AnswerValue) => void;
  onScrollTo: (index: number) => void;
  onSubmit: () => void;
}) {
  const navGroups = buildQuestionGroups(paperTree);
  const isAnswered = (questionIndex: number) => !isAnswerEmpty(answers[questionIndex]);

  return (
    <div className="space-y-6">
      <div className="sticky top-4 z-10 bg-white/95 dark:bg-slate-800/95 backdrop-blur rounded-2xl p-4 border border-slate-200 dark:border-slate-700">
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div className="text-sm text-slate-600 dark:text-slate-300">
            共 <strong className="text-slate-900 dark:text-white">{questions.length}</strong> 题
            {totalFullScore > 0 && <> · 卷面总分 <strong className="text-slate-900 dark:text-white">{totalFullScore}</strong> 分</>}
            {' '}· 已作答 <strong className="text-primary-600 dark:text-primary-400">{answeredCount}</strong> 题
          </div>
          <button
            onClick={onSubmit}
            disabled={submitting || questions.length === 0}
            className="px-5 py-2 bg-primary-500 text-white rounded-lg text-sm font-medium hover:bg-primary-600 transition-colors disabled:opacity-50 flex items-center gap-2"
          >
            {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
            {submitting ? '交卷中...' : '交卷并批改'}
          </button>
        </div>
        {navGroups.length > 0 ? (
          <div className="mt-3 flex flex-wrap gap-2">
            {navGroups.map(group => {
              const progress = groupProgress(group, isAnswered);
              return (
                <div
                  key={group.id}
                  className="flex items-center gap-1.5 rounded-xl border border-dashed border-slate-200 dark:border-slate-600 px-2 py-1"
                >
                  <span className="text-xs text-slate-500 dark:text-slate-400">
                    {group.title}
                    <span className="ml-1 text-slate-400">
                      {progress.answered}/{progress.total}
                    </span>
                  </span>
                  {group.questionIndexes.map(questionIndex => (
                    <button
                      key={questionIndex}
                      onClick={() => onScrollTo(questionIndex)}
                      className={`w-7 h-7 rounded-lg text-xs font-medium border transition-colors ${
                        isAnswered(questionIndex)
                          ? 'bg-primary-500 text-white border-primary-500'
                          : 'bg-white dark:bg-slate-700 text-slate-500 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-primary-400'
                      }`}
                    >
                      {questionIndex + 1}
                    </button>
                  ))}
                </div>
              );
            })}
          </div>
        ) : (
          <div className="mt-3 flex flex-wrap gap-1.5">
            {questions.map(question => (
              <button
                key={question.questionIndex}
                onClick={() => onScrollTo(question.questionIndex)}
                className={`w-8 h-8 rounded-lg text-xs font-medium border transition-colors ${
                  isAnswered(question.questionIndex)
                    ? 'bg-primary-500 text-white border-primary-500'
                    : 'bg-white dark:bg-slate-700 text-slate-500 dark:text-slate-300 border-slate-200 dark:border-slate-600 hover:border-primary-400'
                }`}
              >
                {question.questionIndex + 1}
              </button>
            ))}
          </div>
        )}
      </div>

      {paperTree ? (
        <PaperTreeView
          tree={paperTree}
          answers={answers}
          paperId={paperId}
          onAnswerChange={onAnswerChange}
        />
      ) : (
        questions.map(question => (
          <motion.div
            key={question.questionIndex}
            ref={element => {
              questionRefs.current[question.questionIndex] = element;
            }}
            className="bg-white dark:bg-slate-800 rounded-2xl p-6"
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
              paperId={paperId}
              onChange={value => onAnswerChange(question.questionIndex, value)}
            />
          </motion.div>
        ))
      )}

      <div className="flex justify-end">
        <button
          onClick={onSubmit}
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
