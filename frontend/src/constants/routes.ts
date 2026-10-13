export const ROUTES = {
  login: '/login',
  userAdmin: '/admin/users',
  quizHub: '/quiz-hub',
  quiz: (sessionId: string) => `/quiz/${sessionId}`,
  quizReport: (sessionId: string) => `/quiz/${sessionId}/report`,
  wrongQuestions: '/wrong-questions',
  paperAnalysis: '/papers',
  paperPractice: (sessionId: string) => `/papers?session=${sessionId}`,
  interview: '/interview',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  resumeHistory: '/history',
  knowledgebaseUpload: '/knowledgebase/upload',
} as const;

export const ROUTE_PATTERNS = {
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
} as const;
