import { AlertCircle, ClipboardList, Loader2 } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import PaperRecognitionPanel from '../components/paper-recognition/PaperRecognitionPanel';
import { PaperAnalysisPracticePanel } from '../components/paper-analysis/PaperAnalysisPracticePanel';
import { PaperAnalysisReadyPanel } from '../components/paper-analysis/PaperAnalysisReadyPanel';
import { PaperAnalysisReportPanel } from '../components/paper-analysis/PaperAnalysisReportPanel';
import { PaperAnalysisStageCard } from '../components/paper-analysis/PaperAnalysisStageCard';
import { ROUTES } from '../constants/routes';
import { usePaperAnalysis } from '../hooks/usePaperAnalysis';
import UploadPage from './UploadPage';

/**
 * 试卷分析页：上传做过的试卷 / 练习材料 → 结构化切题 → 整卷作答 → 一次交卷 → 批改报告。
 *
 * URL 参数用于恢复现场：
 * - `?paper=123` 试卷已上传，展示切题进度与解析摘要
 * - `?session=xxx` 练习已生成，恢复作答或查看报告
 */
export default function PaperAnalysisPage() {
  const navigate = useNavigate();
  const {
    stage,
    paperId,
    paperDetail,
    sessionPaperId,
    questions,
    paperTree,
    answers,
    report,
    submitting,
    error,
    questionRefs,
    scoreSummary,
    weakCategories,
    answeredCount,
    totalFullScore,
    setAnswers,
    openPaper,
    handleReanalyze,
    handleRetryInput,
    handleStartPractice,
    handleSubmit,
    handleRecognitionConfirmed,
    scrollToQuestion,
  } = usePaperAnalysis();

  return (
    <div className="max-w-5xl mx-auto px-4 pb-16">
      <div className="flex items-center gap-3 py-6">
        <div className="w-10 h-10 rounded-xl bg-primary-500/10 flex items-center justify-center">
          <ClipboardList className="w-5 h-5 text-primary-500" />
        </div>
        <div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-white">试卷分析</h1>
          <p className="text-sm text-slate-500 dark:text-slate-400">
            上传做过的试卷或练习材料，AI 切题后整卷作答，自动批改并给出学情反馈
          </p>
        </div>
      </div>

      {error && (
        <div className="mb-6 flex items-start gap-3 p-4 rounded-xl bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800">
          <AlertCircle className="w-5 h-5 text-red-500 flex-shrink-0 mt-0.5" />
          <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
        </div>
      )}

      {stage === 'upload' && (
        <UploadPage
          embedded
          onBack={() => navigate(ROUTES.resumeHistory)}
          onOpenPaper={openPaper}
        />
      )}

      {stage === 'input' && (
        <PaperAnalysisStageCard
          icon={<Loader2 className="w-8 h-8 text-primary-500 animate-spin" />}
          title={paperDetail?.inputStatus === 'FAILED' ? '文件解析失败' : '正在解析文件'}
          description={paperDetail?.inputStatus === 'FAILED'
            ? '原始文件已保留，可以重新解析；如果文件本身有问题，也可以返回重新上传。'
            : '正在读取原始文件并整理页面，完成后会自动进入识别或切题'}
          action={paperDetail?.inputStatus === 'FAILED' ? (
            <button
              type="button"
              onClick={() => void handleRetryInput()}
              className="rounded-lg bg-primary-500 px-5 py-2 text-sm font-medium text-white hover:bg-primary-600"
            >
              重新解析
            </button>
          ) : undefined}
        />
      )}

      {stage === 'extracting' && (
        <PaperAnalysisStageCard
          icon={<Loader2 className="w-8 h-8 text-primary-500 animate-spin" />}
          title="AI 正在切题"
          description="正在把整份材料拆成一道一道题目，并补全知识点、参考答案与评分标准"
        />
      )}

      {stage === 'recognition' && paperId && (
        <PaperRecognitionPanel
          paperId={paperId}
          hasExistingExtraction={Boolean(paperDetail?.analyses?.length)}
          onConfirmed={handleRecognitionConfirmed}
        />
      )}

      {stage === 'ready' && (
        <PaperAnalysisReadyPanel
          detail={paperDetail}
          onStart={handleStartPractice}
          onReanalyze={handleReanalyze}
        />
      )}

      {stage === 'practice' && (
        <PaperAnalysisPracticePanel
          questions={questions}
          paperTree={paperTree}
          answers={answers}
          answeredCount={answeredCount}
          totalFullScore={totalFullScore}
          paperId={paperId ?? sessionPaperId ?? undefined}
          submitting={submitting}
          questionRefs={questionRefs}
          onAnswerChange={(index, value) =>
            setAnswers(previous => ({ ...previous, [index]: value }))
          }
          onScrollTo={scrollToQuestion}
          onSubmit={handleSubmit}
        />
      )}

      {stage === 'grading' && (
        <PaperAnalysisStageCard
          icon={<Loader2 className="w-8 h-8 text-primary-500 animate-spin" />}
          title="AI 正在批改"
          description="正在逐题判分并汇总知识板块表现，通常需要十几秒"
        />
      )}

      {stage === 'report' && report && (
        <PaperAnalysisReportPanel
          report={report}
          summary={scoreSummary}
          weakCategories={weakCategories}
          onRetry={handleStartPractice}
        />
      )}
    </div>
  );
}
