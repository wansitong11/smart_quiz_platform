import type { ModelOption } from '../api/aiCost';

export const AI_FEATURE_NAMES: Record<string, string> = {
  QUIZ_GENERATE: '模拟测验出题',
  PAPER_IMPORT: '试卷导入',
  PAPER_RECOGNIZE: '试卷识图',
  PAPER_EXTRACTION: '试卷切题',
  KB_QA: '知识库问答',
  KB_VECTORIZE: '知识库向量化',
  KB_QUESTION_GEN: '知识库出题',
  INTERVIEW_QUESTION_GEN: '模拟练习出题',
  INTERVIEW_SCHEDULE_PARSE: '面试邀约解析',
  ANSWER_EVALUATE: '作答批改',
  VOICE_EVALUATE: '语音面试评估',
  UNKNOWN: '其他',
};

export const AI_MODALITY_NAMES: Record<string, string> = {
  TEXT: '文字',
  VISION: '识图',
  EMBEDDING: '向量',
};

export const AI_PROVIDER_NAMES: Record<string, string> = {
  dashscope: 'DashScope',
  'dashscope-vl': 'DashScope-VL',
  'dashscope-3.7': 'DashScope 3.7',
  'dashscope-3.7-vl': 'DashScope 3.7 视觉',
  glm: '智谱 GLM',
  'glm-free': '智谱 GLM（免费）',
  'glm-vl-free': '智谱 GLM 视觉（免费）',
  'siliconflow-free': '硅基流动（免费）',
  deepseek: 'DeepSeek',
  kimi: 'Kimi',
};

export function featureName(feature: string): string {
  return AI_FEATURE_NAMES[feature] ?? '其他';
}

export function modalityName(modality: string): string {
  return AI_MODALITY_NAMES[modality] ?? modality;
}

export function modelLabel(model: ModelOption): string {
  const provider = AI_PROVIDER_NAMES[model.provider] ?? model.provider;
  return `${provider} / ${model.model} / ${modalityName(model.modality)}`;
}
