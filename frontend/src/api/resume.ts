import { request } from './request';
import type { UploadResponse } from '../types/resume';

export const resumeApi = {
  /**
   * 上传简历并获取分析结果
   */
  async uploadAndAnalyze(file: File): Promise<UploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    return request.upload<UploadResponse>('/api/resumes/upload', formData);
  },

  /**
   * 健康检查
   */
  async healthCheck(): Promise<{ status: string; service: string }> {
    return request.get('/api/resumes/health');
  },

  /**
   * 重新切题（重新解析该材料并生成练习）
   */
  async reanalyze(resumeId: number): Promise<void> {
    return request.post<void>(`/api/resumes/${resumeId}/reanalyze`);
  },
};
