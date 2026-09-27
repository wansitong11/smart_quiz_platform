import { motion } from 'framer-motion';
import { formatDateTime } from '../utils/date';
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Download,
  ListChecks,
  Loader2,
  PlayCircle,
  RefreshCw,
  Target,
} from 'lucide-react';
import type { AnalyzeStatus } from '../api/history';

interface PaperAnalysisSummary {
  id?: number;
  questionCount?: number;
  totalScore?: number;
  summary?: string;
  analyzedAt?: string;
  practiceSessionId?: string | null;
  topics?: string[];
  warnings?: string[];
}

interface AnalysisPanelProps {
  analysis: PaperAnalysisSummary | null | undefined;
  analysisStatus?: AnalyzeStatus;
  analysisError?: string;
  onExport: () => void;
  exporting: boolean;
  onReanalyze?: () => void;
  reanalyzing?: boolean;
  /** 开始（或继续）一次作答；具体由外层决定是复用未交卷的练习还是新开一次 */
  onStartPractice?: () => void;
}

/**
 * 试卷解析面板：展示结构化切题的结果概览（题量、卷面总分、知识板块分布、解析告警）。
 */
export default function AnalysisPanel({
  analysis,
  analysisStatus,
  analysisError,
  onExport,
  exporting,
  onReanalyze,
  reanalyzing,
  onStartPractice,
}: AnalysisPanelProps) {
  const isProcessing = analysisStatus === 'PENDING' || analysisStatus === 'PROCESSING';
  const isFailed = analysisStatus === 'FAILED';
  const hasResult = !isFailed && analysis != null && (analysis.questionCount ?? 0) > 0;

  if (isProcessing) {
    return (
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-12 text-center">
        <div className="w-16 h-16 mx-auto mb-6 bg-blue-100 dark:bg-blue-900/50 rounded-full flex items-center justify-center">
          <Loader2 className="w-8 h-8 text-blue-500 dark:text-blue-400 animate-spin" />
        </div>
        <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">
          AI 正在切题...
        </h3>
        <p className="text-slate-500 dark:text-slate-400 mb-4">
          正在把整份材料拆成一道一道题目，并补全知识点、参考答案与评分标准
        </p>
        <p className="text-sm text-slate-400 dark:text-slate-500">页面会自动刷新显示结果</p>
      </div>
    );
  }

  if (isFailed || (!hasResult && analysisStatus === 'COMPLETED')) {
    return (
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-12 text-center">
        <div className="w-16 h-16 mx-auto mb-6 bg-red-100 dark:bg-red-900/50 rounded-full flex items-center justify-center">
          <AlertCircle className="w-8 h-8 text-red-500 dark:text-red-400" />
        </div>
        <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">
          {isFailed ? '切题失败' : '暂未识别到题目'}
        </h3>
        <p className="text-slate-500 dark:text-slate-400 mb-4">
          {analysisError || '请确认上传的是有文字层的电子版试卷或练习材料，扫描件与照片暂不支持'}
        </p>
        {onReanalyze && (
          <motion.button
            onClick={onReanalyze}
            disabled={reanalyzing}
            className="px-6 py-2.5 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-colors disabled:opacity-50 flex items-center gap-2 mx-auto"
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
          >
            <RefreshCw className={`w-4 h-4 ${reanalyzing ? 'animate-spin' : ''}`} />
            {reanalyzing ? '重新切题中...' : '重新切题'}
          </motion.button>
        )}
      </div>
    );
  }

  if (!analysis) {
    return (
      <div className="bg-white dark:bg-slate-800 rounded-2xl p-12 text-center">
        <div className="w-16 h-16 mx-auto mb-6 bg-yellow-100 dark:bg-yellow-900/50 rounded-full flex items-center justify-center">
          <Clock className="w-8 h-8 text-yellow-500 dark:text-yellow-400" />
        </div>
        <h3 className="text-xl font-semibold text-slate-700 dark:text-slate-300 mb-2">等待切题</h3>
        <p className="text-slate-500 dark:text-slate-400">材料已上传，即将开始结构化切题</p>
      </div>
    );
  }

  const topics = analysis.topics ?? [];
  const warnings = analysis.warnings ?? [];

  return (
    <div className="space-y-6">
      <motion.div
        className="bg-white dark:bg-slate-800 rounded-2xl p-6"
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
      >
        <div className="flex items-center justify-between mb-6 flex-wrap gap-3">
          <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400">
            <Target className="w-5 h-5" />
            <span className="font-semibold">试卷解析</span>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {onStartPractice && analysis.practiceSessionId && (
              <motion.button
                onClick={() => onStartPractice()}
                className="px-4 py-2 bg-primary-500 text-white rounded-lg text-sm font-medium hover:bg-primary-600 transition-all flex items-center gap-2"
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
              >
                <PlayCircle className="w-4 h-4" />
                开始作答
              </motion.button>
            )}
            {onReanalyze && (
              <motion.button
                onClick={onReanalyze}
                disabled={reanalyzing}
                className="px-4 py-2 border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 rounded-lg text-slate-600 dark:text-slate-300 text-sm font-medium hover:bg-slate-50 dark:hover:bg-slate-600 transition-all disabled:opacity-50 flex items-center gap-2"
                whileHover={{ scale: 1.02 }}
                whileTap={{ scale: 0.98 }}
              >
                <RefreshCw className={`w-4 h-4 ${reanalyzing ? 'animate-spin' : ''}`} />
                {reanalyzing ? '重新切题中...' : '重新切题'}
              </motion.button>
            )}
            <motion.button
              onClick={onExport}
              disabled={exporting}
              className="px-4 py-2 border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700 rounded-lg text-slate-600 dark:text-slate-300 text-sm font-medium hover:bg-slate-50 dark:hover:bg-slate-600 transition-all disabled:opacity-50 flex items-center gap-2"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
            >
              <Download className="w-4 h-4" />
              {exporting ? '导出中...' : '导出解析报告'}
            </motion.button>
          </div>
        </div>

        <div className="bg-gradient-to-br from-indigo-50 dark:from-indigo-900/30 to-slate-50 dark:to-slate-800 rounded-xl p-6">
          <p className="text-slate-800 dark:text-white leading-relaxed mb-6">
            {analysis.summary || '已完成结构化切题。'}
          </p>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div className="bg-white dark:bg-slate-800 rounded-xl p-5">
              <span className="text-sm font-semibold text-indigo-600 dark:text-indigo-400 block mb-2">
                识别题量
              </span>
              <span className="text-3xl font-bold text-slate-900 dark:text-white">
                {analysis.questionCount ?? 0}
              </span>
              <span className="text-sm text-slate-500 dark:text-slate-400"> 道</span>
            </div>
            <div className="bg-white dark:bg-slate-800 rounded-xl p-5">
              <span className="text-sm font-semibold text-indigo-600 dark:text-indigo-400 block mb-2">
                卷面总分
              </span>
              <span className="text-3xl font-bold text-slate-900 dark:text-white">
                {analysis.totalScore ?? 0}
              </span>
              <span className="text-sm text-slate-500 dark:text-slate-400"> 分</span>
            </div>
            <div className="bg-white dark:bg-slate-800 rounded-xl p-5">
              <span className="text-sm font-semibold text-indigo-600 dark:text-indigo-400 block mb-2">
                解析时间
              </span>
              <span className="text-sm text-slate-700 dark:text-slate-300">
                {analysis.analyzedAt ? formatDateTime(analysis.analyzedAt) : '—'}
              </span>
            </div>
          </div>
        </div>
      </motion.div>

      {topics.length > 0 && (
        <motion.div
          className="bg-white dark:bg-slate-800 rounded-2xl p-6"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.1 }}
        >
          <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400 mb-4">
            <ListChecks className="w-5 h-5" />
            <span className="font-semibold">知识板块分布</span>
          </div>
          <div className="flex flex-wrap gap-2">
            {topics.map((topic, index) => {
              const [label, count, score] = topic.split('|');
              return (
                <span
                  key={index}
                  className="px-3 py-1.5 bg-indigo-50 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 border border-indigo-200 dark:border-indigo-800 rounded-lg text-sm font-medium"
                >
                  {label}
                  <span className="ml-2 text-indigo-500 dark:text-indigo-400 text-xs">
                    {count}
                    {score ? ` · ${score}` : ''}
                  </span>
                </span>
              );
            })}
          </div>
        </motion.div>
      )}

      {warnings.length > 0 && (
        <motion.div
          className="bg-white dark:bg-slate-800 rounded-2xl p-6"
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ delay: 0.2 }}
        >
          <div className="flex items-center gap-2 text-amber-600 dark:text-amber-400 mb-4">
            <AlertTriangle className="w-5 h-5" />
            <span className="font-semibold">解析告警（{warnings.length}）</span>
          </div>
          <ul className="space-y-2">
            {warnings.map((warning, index) => (
              <li
                key={index}
                className="flex items-start gap-2 text-sm text-slate-600 dark:text-slate-300"
              >
                <CheckCircle2 className="w-4 h-4 mt-0.5 text-amber-500 flex-shrink-0" />
                <span>{warning}</span>
              </li>
            ))}
          </ul>
        </motion.div>
      )}
    </div>
  );
}
