// 试卷 / 练习材料解析摘要（结构化切题结果概览）
export interface ResumeAnalysisResponse {
  id: number;
  questionCount: number;
  totalScore: number;
  summary: string;
  analyzedAt: string;
  practiceSessionId?: string | null;
  topics: string[];
  warnings: string[];
}

// 存储信息
export interface StorageInfo {
  fileKey: string;
  fileUrl: string;
  resumeId?: number;
}

// 上传API完整响应（异步模式：analysis 可能为空）
export interface UploadResponse {
  analysis?: ResumeAnalysisResponse;
  resume?: {
    id: number;
    filename: string;
    analysisStatus: string;
  };
  storage: StorageInfo;
  /** 任务是否成功投递到异步队列；false 时简历已保存但状态为 FAILED，可重试 */
  enqueueAccepted: boolean;
  duplicate: boolean;
  message: string;
}

export interface ApiError {
  error: string;
  detectedType?: string;
  allowedTypes?: string[];
}
