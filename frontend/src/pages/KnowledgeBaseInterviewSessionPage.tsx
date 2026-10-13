import { useState } from 'react';
import { Navigate, useNavigate, useParams } from 'react-router-dom';
import QuizPage from './QuizPage';

/**
 * 资料测验作答页。
 * <p>
 * 以前这里直接渲染 `InterviewPage`（对话式逐题面试），现在改成与「模拟测验」完全同一套
 * 整卷作答：答题卡 + 材料原文随题 + 选择题点选 / 填空输入框 / 主观题文本域 + 一次交卷 + 报告。
 * 会话本身就是 practice_sessions，交卷与批改直接复用现有链路，无需新的后端接口。
 */
export default function KnowledgeBaseInterviewSessionPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [mode, setMode] = useState<'attempt' | 'report'>('attempt');

  if (!sessionId) {
    return <Navigate to="/knowledgebase-interview" replace />;
  }

  return (
    <QuizPage
      sessionId={sessionId}
      mode={mode}
      backLabel="返回资料题库"
      onBack={() => navigate('/knowledgebase-interview')}
      // 交卷后原地切到报告，与模拟测验的行为一致
      onFinished={() => setMode('report')}
    />
  );
}
