import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom';
import Layout from './components/Layout';
import { useEffect, useState, Suspense, lazy } from 'react';
import { historyApi, type InterviewDetail } from './api/history';
import type { Difficulty } from './components/UnifiedInterviewModal';
import type { CategoryDTO } from './api/skill';
import { Loader2 } from 'lucide-react';
import { ROUTE_PATTERNS, ROUTES } from './constants/routes';
import { AuthProvider } from './context/AuthContext';
import RequireAuth from './components/RequireAuth';

// Lazy load components
const UploadPage = lazy(() => import('./pages/UploadPage'));
const PaperAnalysisPage = lazy(() => import('./pages/PaperAnalysisPage'));
const HistoryList = lazy(() => import('./pages/HistoryPage'));
const ResumeDetailPage = lazy(() => import('./pages/ResumeDetailPage'));
const Interview = lazy(() => import('./pages/InterviewPage'));
const InterviewHistoryPage = lazy(() => import('./pages/InterviewHistoryPage'));
const KnowledgeBaseQueryPage = lazy(() => import('./pages/KnowledgeBaseQueryPage'));
const KnowledgeBaseUploadPage = lazy(() => import('./pages/KnowledgeBaseUploadPage'));
const KnowledgeBaseManagePage = lazy(() => import('./pages/KnowledgeBaseManagePage'));
const KnowledgeBaseInterviewPage = lazy(() => import('./pages/KnowledgeBaseInterviewLandingPage'));
const KnowledgeBaseInterviewQuestionsPage = lazy(() => import('./pages/KnowledgeBaseInterviewQuestionsPage'));
const KnowledgeBaseInterviewSessionPage = lazy(() => import('./pages/KnowledgeBaseInterviewSessionPage'));
// 语音面试暂时下线（前端入口注释，页面文件保留；后端 WebSocket 由 app.voice-interview.enabled 控制）
// const VoiceInterviewPage = lazy(() => import('./pages/VoiceInterviewPage'));
// const VoiceInterviewEvaluationPage = lazy(() => import('./pages/VoiceInterviewEvaluationPage'));
// 学习计划模块暂时下线（前端入口注释，页面与后端接口保留，恢复时取消注释即可）
// const InterviewSchedulePage = lazy(() => import('./pages/InterviewSchedulePage'));
const InterviewHubPage = lazy(() => import('./pages/InterviewHubPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const LoginPage = lazy(() => import('./pages/LoginPage'));
const UserAdminPage = lazy(() => import('./pages/UserAdminPage'));
const QuizHubPage = lazy(() => import('./pages/QuizHubPage'));
const QuizPage = lazy(() => import('./pages/QuizPage'));
const WrongQuestionsPage = lazy(() => import('./pages/WrongQuestionsPage'));
const InterviewDetailPanel = lazy(() => import('./components/InterviewDetailPanel'));

// Loading component
const Loading = () => (
  <div className="flex items-center justify-center min-h-[50vh]">
    <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
  </div>
);

// 上传页面包装器
function UploadPageWrapper() {
  const navigate = useNavigate();

  return <UploadPage onBack={() => navigate(ROUTES.resumeHistory)} />;
}

// 模拟测验包装器：作答与报告共用同一个页面组件，用 mode 区分
function QuizPageWrapper({ mode }: { mode: 'attempt' | 'report' }) {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();

  if (!sessionId) {
    return <Navigate to={ROUTES.quizHub} replace />;
  }

  return (
    <QuizPage
      sessionId={sessionId}
      mode={mode}
      onBack={() => navigate(ROUTES.quizHub)}
      onFinished={id => navigate(ROUTES.quizReport(id), { replace: true })}
    />
  );
}

// 历史记录列表包装器
function HistoryListWrapper() {
  const navigate = useNavigate();

  const handleSelectResume = (id: number) => {
    navigate(`/history/${id}`);
  };

  return <HistoryList onSelectResume={handleSelectResume} />;
}

// 简历详情包装器
function ResumeDetailWrapper() {
  const { resumeId } = useParams<{ resumeId: string }>();
  const navigate = useNavigate();

  if (!resumeId) {
    return <Navigate to="/history" replace />;
  }

  const handleBack = () => {
    navigate('/history');
  };

  const handleStartPractice = (sessionId: string) => {
    navigate(ROUTES.paperPractice(sessionId));
  };

  return (
    <ResumeDetailPage
      resumeId={parseInt(resumeId, 10)}
      onBack={handleBack}
      onStartPractice={handleStartPractice}
    />
  );
}

interface InterviewEntryState {
  resumeId?: number;
  resumeText?: string;
  sessionIdToResume?: string;
  interviewConfig?: {
    skillId?: string;
    difficulty?: Difficulty;
    questionCount?: number;
    llmProvider?: string;
    customCategories?: CategoryDTO[];
    jdText?: string;
  };
}

// 模拟面试包装器
function InterviewWrapper() {
  const { resumeId, requestId, activeSessionId } = useParams<{
    resumeId: string;
    requestId: string;
    activeSessionId: string;
  }>();
  const navigate = useNavigate();
  const location = useLocation();
  const entryState = (location.state as InterviewEntryState | undefined) ?? {};
  const [resumeText, setResumeText] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const effectiveResumeId = resumeId ? parseInt(resumeId, 10) : entryState.resumeId;

  useEffect(() => {
    // 优先从location state获取resumeText
    const stateText = entryState.resumeText;
    if (stateText) {
      setResumeText(stateText);
      setLoading(false);
    } else if (effectiveResumeId) {
      // 如果没有，从API获取简历详情
      historyApi.getResumeDetail(effectiveResumeId)
        .then(resume => {
          setResumeText(resume.resumeText);
          setLoading(false);
        })
        .catch(err => {
          console.error('获取简历文本失败', err);
          setLoading(false);
        });
    } else {
      setLoading(false);
    }
  }, [effectiveResumeId, entryState.resumeText]);

  const handleBack = () => {
    if (effectiveResumeId) {
      navigate(`/history/${effectiveResumeId}`, { replace: false });
      return;
    }
    navigate('/history', { replace: false });
  };

  const handleInterviewComplete = () => {
    // 面试完成后跳转到面试记录页
    navigate('/interviews');
  };

  const handleSessionCreated = (sessionId: string) => {
    navigate(ROUTES.interviewSession(sessionId), { replace: true, state: entryState });
  };

  if (!requestId && !activeSessionId && !entryState.sessionIdToResume) {
    return (
      <Navigate
        to={ROUTES.interviewCreate(crypto.randomUUID())}
        replace
        state={{ ...entryState, resumeId: effectiveResumeId }}
      />
    );
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-screen">
        <div className="text-center">
          <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full mx-auto mb-4 animate-spin" />
          <p className="text-slate-500">加载中...</p>
        </div>
      </div>
    );
  }

  return (
    <Interview
      resumeText={resumeText}
      resumeId={effectiveResumeId}
      sessionIdToResume={activeSessionId ?? entryState.sessionIdToResume}
      requestId={requestId}
      initialConfig={entryState.interviewConfig}
      onBack={handleBack}
      onSessionCreated={handleSessionCreated}
      onInterviewComplete={handleInterviewComplete}
    />
  );
}

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Suspense fallback={<Loading />}>
          <Routes>
            {/* 登录页：唯一不需要登录的路由 */}
            <Route path={ROUTES.login.slice(1)} element={<LoginPage />} />

            <Route
              path="/"
              element={(
                <RequireAuth>
                  <Layout />
                </RequireAuth>
              )}
            >
            {/* 默认重定向到简历管理页面 */}
            <Route index element={<Navigate to="/history" replace />} />

            {/* 上传页面 */}
            <Route path="upload" element={<UploadPageWrapper />} />

            {/* 试卷分析：上传 → 切题 → 整卷作答 → 报告 */}
            <Route path="papers" element={<PaperAnalysisPage />} />

            {/* 历史记录列表（简历库） */}
            <Route path="history" element={<HistoryListWrapper />} />

            {/* 简历详情 */}
            <Route path="history/:resumeId" element={<ResumeDetailWrapper />} />

            {/* 面试中心 */}
            <Route path="interview-hub" element={<InterviewHubPage />} />

            {/* 面试记录列表 */}
            <Route path="interviews" element={<InterviewHistoryWrapper />} />

            {/* 面试详情报告 */}
            <Route path="interviews/:sessionId" element={<InterviewDetailPageWrapper />} />

            {/* 模拟面试（通用入口） */}
            <Route path={ROUTES.interview.slice(1)} element={<InterviewWrapper />} />

            {/* 创建中的文本面试，请求 ID 用于刷新幂等 */}
            <Route path={ROUTE_PATTERNS.interviewCreate} element={<InterviewWrapper />} />

            {/* 进行中的文本面试，刷新时按会话 ID 恢复 */}
            <Route path={ROUTE_PATTERNS.interviewSession} element={<InterviewWrapper />} />

            {/* 模拟面试 */}
            <Route path="interview/:resumeId" element={<InterviewWrapper />} />

            {/* 语音面试暂时下线：路由注释掉，页面文件与后端保留，恢复时取消注释即可 */}
            {/* <Route path="voice-interview" element={<VoiceInterviewPageWrapper />} /> */}
            {/* <Route path="voice-interview/:sessionId/evaluation" element={<VoiceInterviewEvaluationPage />} /> */}

            {/* 知识库管理 */}
            <Route path="knowledgebase" element={<KnowledgeBaseManagePageWrapper />} />

            {/* 知识库面试 */}
            <Route path="knowledgebase-interview" element={<KnowledgeBaseInterviewPage />} />
            <Route path="knowledgebase-interview/:knowledgeBaseId/questions" element={<KnowledgeBaseInterviewQuestionsPage />} />
            <Route path="knowledgebase-interview/:knowledgeBaseId/interviews" element={<KnowledgeBaseInterviewHistoryWrapper />} />
            <Route path="knowledgebase-interview/:knowledgeBaseId/interviews/:sessionId" element={<InterviewDetailPageWrapper />} />
            <Route path="knowledgebase-interview/:sessionId" element={<KnowledgeBaseInterviewSessionPage />} />

            {/* 知识库上传 */}
            <Route path="knowledgebase/upload" element={<KnowledgeBaseUploadPageWrapper />} />

            {/* 学习计划模块暂时下线：路由注释掉，页面与后端接口保留 */}
            {/* <Route path="interview-schedule" element={<InterviewSchedulePage />} /> */}

            {/* 设置 */}
            <Route path="settings" element={<SettingsPage />} />

            {/* 问答助手（知识库聊天） */}
            <Route path="knowledgebase/chat" element={<KnowledgeBaseQueryPageWrapper />} />

            {/* 模拟测验：组卷 → 整卷作答 → 报告 */}
            <Route path="quiz-hub" element={<QuizHubPage />} />
            <Route path="wrong-questions" element={<WrongQuestionsPage />} />
            <Route
              path="quiz/:sessionId"
              element={(
                <QuizPageWrapper mode="attempt" />
              )}
            />
            <Route
              path="quiz/:sessionId/report"
              element={(
                <QuizPageWrapper mode="report" />
              )}
            />

            {/* 账号管理（需 user:manage 权限，页面内再兜一层判断） */}
            <Route path="admin/users" element={<UserAdminPage />} />
            </Route>

          </Routes>
        </Suspense>
      </BrowserRouter>
    </AuthProvider>
  );
}

// 答题记录页面包装器（只看模拟测验与错题重练，与试卷/面试隔离）
function InterviewHistoryWrapper() {
  const navigate = useNavigate();

  const handleBack = () => {
    navigate('/quiz-hub');
  };

  const handleViewInterview = async (sessionId: string) => {
    // 未交卷时报告页会提示「继续作答」，两种状态都能安全进入
    navigate(`/quiz/${sessionId}/report`);
  };

  const handleContinueInterview = (sessionId: string) => {
    navigate(`/quiz/${sessionId}`);
  };

  return (
    <InterviewHistoryPage
      onBack={handleBack}
      onViewInterview={handleViewInterview}
      onContinueInterview={handleContinueInterview}
      sourceTypes="QUIZ,QUIZ_WRONG"
    />
  );
}

// 知识库面试记录页面包装器
function KnowledgeBaseInterviewHistoryWrapper() {
  const navigate = useNavigate();
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId: string }>();
  const id = knowledgeBaseId ? Number(knowledgeBaseId) : NaN;

  if (Number.isNaN(id)) {
    return <Navigate to="/knowledgebase-interview" replace />;
  }

  const handleBack = () => {
    navigate(`/knowledgebase-interview/${id}/questions`);
  };

  const handleViewInterview = (sessionId: string) => {
    navigate(`/knowledgebase-interview/${id}/interviews/${sessionId}`);
  };

  const handleContinueInterview = (sessionId: string) => {
    navigate(`/knowledgebase-interview/${sessionId}`, { state: { knowledgeBaseId: id } });
  };

  return (
    <InterviewHistoryPage
      knowledgeBaseId={id}
      onBack={handleBack}
      onViewInterview={handleViewInterview}
      onContinueInterview={handleContinueInterview}
    />
  );
}

// 面试详情报告页面包装器
function InterviewDetailPageWrapper() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId?: string }>();
  const navigate = useNavigate();
  const [interview, setInterview] = useState<InterviewDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const backTarget = knowledgeBaseId
    ? `/knowledgebase-interview/${knowledgeBaseId}/interviews`
    : '/interviews';

  useEffect(() => {
    if (!sessionId) {
      navigate(backTarget);
      return;
    }
    historyApi.getInterviewDetail(sessionId)
      .then(detail => {
        setInterview(detail);
        setLoading(false);
      })
      .catch(() => {
        setError('加载答题详情失败');
        setLoading(false);
      });
  }, [sessionId, navigate, backTarget]);

  if (loading) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
      </div>
    );
  }

  if (error || !interview) {
    return (
      <div className="flex items-center justify-center min-h-[50vh]">
        <div className="text-center">
          <p className="text-red-500 mb-4">{error || '答题记录不存在'}</p>
          <button
            onClick={() => navigate(backTarget)}
            className="px-5 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600"
          >
            返回答题记录
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="flex items-center gap-3 mb-6">
        <button
          onClick={() => navigate(backTarget)}
          className="p-2 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-lg transition-colors"
        >
          <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </button>
        <h1 className="text-xl font-bold text-slate-900 dark:text-white">
          答题详情 #{sessionId!.slice(-8)}
        </h1>
      </div>
      <InterviewDetailPanel interview={interview} />
    </div>
  );
}
function KnowledgeBaseManagePageWrapper() {
  const navigate = useNavigate();

  const handleUpload = () => {
    navigate(ROUTES.knowledgebaseUpload);
  };

  const handleUploadToCategory = (categoryCode: string) => {
    navigate(`${ROUTES.knowledgebaseUpload}?categoryCode=${encodeURIComponent(categoryCode)}`);
  };

  const handleChat = () => {
    navigate('/knowledgebase/chat');
  };

  return <KnowledgeBaseManagePage
    onUpload={handleUpload}
    onChat={handleChat}
    onUploadToCategory={handleUploadToCategory}
  />;
}

// 知识库问答页面包装器
function KnowledgeBaseQueryPageWrapper() {
  const navigate = useNavigate();
  const location = useLocation();
  const isChatMode = location.pathname === '/knowledgebase/chat';

  const handleBack = () => {
    if (isChatMode) {
      navigate('/knowledgebase');
    } else {
      navigate('/history');
    }
  };

  const handleUpload = () => {
    navigate(ROUTES.knowledgebaseUpload);
  };

  return <KnowledgeBaseQueryPage onBack={handleBack} onUpload={handleUpload} />;
}

// 知识库上传页面包装器
function KnowledgeBaseUploadPageWrapper() {
  const navigate = useNavigate();

  const handleBack = () => {
    navigate('/knowledgebase');
  };

  return <KnowledgeBaseUploadPage onBack={handleBack} />;
}

// 语音面试页面包装器（随语音面试一起暂时下线）
// function VoiceInterviewPageWrapper() {
//   return <VoiceInterviewPage />;
// }

export default App;
