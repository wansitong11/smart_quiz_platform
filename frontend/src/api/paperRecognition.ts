import { request } from './request';
import type { PaperRecognition, PaperUploadResult, StructurePreview } from '../types/paperRecognition';

/** 扫描件 / 拍照试卷识别接口 */
export const paperRecognitionApi = {
  async upload(files: File[]): Promise<PaperUploadResult> {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file));
    return request.upload<PaperUploadResult>('/api/papers/upload', formData);
  },

  async get(paperId: number): Promise<PaperRecognition> {
    return request.get<PaperRecognition>(`/api/papers/${paperId}/recognition`);
  },

  async updatePageText(paperId: number, pageNo: number, text: string): Promise<void> {
    return request.put<void>(`/api/papers/${paperId}/pages/${pageNo}`, { text });
  },

  async retryPage(paperId: number, pageNo: number, forceVision = false): Promise<void> {
    return request.post<void>(
      `/api/papers/${paperId}/pages/${pageNo}/retry?forceVision=${forceVision}`,
    );
  },

  async deletePage(paperId: number, pageNo: number): Promise<void> {
    return request.delete<void>(`/api/papers/${paperId}/pages/${pageNo}`);
  },

  async confirm(paperId: number, ignoreFailed = false): Promise<void> {
    return request.post<void>(`/api/papers/${paperId}/recognition/confirm`, { ignoreFailed });
  },

  async retryRecognition(paperId: number, all = false): Promise<void> {
    return request.post<void>(`/api/papers/${paperId}/recognition/retry`, { all });
  },

  /** 页图走鉴权请求拿 Blob（<img src> 不会带 Authorization 头） */
  async fetchPageImage(paperId: number, pageNo: number): Promise<Blob> {
    return request.download(`/api/papers/${paperId}/pages/${pageNo}/image`);
  },

  async fetchAsset(paperId: number, assetKey: string): Promise<Blob> {
    return request.download(`/api/papers/${paperId}/assets/${encodeURIComponent(assetKey)}`);
  },

  async previewStructure(paperId: number): Promise<StructurePreview> {
    return request.post<StructurePreview>(`/api/papers/${paperId}/structure/preview`);
  },
};
