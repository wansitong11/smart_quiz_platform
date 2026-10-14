import { knowledgeBaseApi } from './knowledgebase';
import { resumeApi } from './resume';
import { historyApi } from './history';
import type { BatchUploadAdapter } from '../types/batchUpload';
import { getUploadOutcome, normalizeProcessingStatus } from '../utils/batchUpload';

/**
 * 资料上传适配器。
 * <p>
 * 「导入到哪个分类」是上传那一刻才需要的信息，所以通过 getCategoryCode 读取当前选择。
 * 页面用 ref 支撑这个 getter，适配器自身引用保持稳定，上传队列不会因切换分类被重建。
 * </p>
 */
export function createKnowledgeBaseUploadAdapter(
  getCategoryCode: () => string | undefined = () => undefined,
  getVisibility: () => 'PRIVATE' | 'SHARED' | undefined = () => undefined,
): BatchUploadAdapter {
  return {
    processLabel: '向量化',
    async upload(file, customName) {
      const result = await knowledgeBaseApi.uploadKnowledgeBase(
        file, customName, getCategoryCode(), getVisibility());
      if (!result.knowledgeBase?.id) throw new Error('上传结果缺少知识库 ID，请重试');
      return {
        entityId: result.knowledgeBase.id,
        duplicate: result.duplicate,
        ...getUploadOutcome(normalizeProcessingStatus(result.knowledgeBase.vectorStatus),
          result.enqueueAccepted, result.message, '向量化'),
      };
    },
    async getStatus(id, signal) {
      const result = await knowledgeBaseApi.getKnowledgeBase(id, signal);
      return { id: result.id, status: result.vectorStatus, error: result.vectorError };
    },
    retry: id => knowledgeBaseApi.revectorize(id),
  };
}

/** 不指定分类的默认适配器，等价于导入到「未分类」 */
export const knowledgeBaseUploadAdapter: BatchUploadAdapter =
  createKnowledgeBaseUploadAdapter();

/** 试卷 / 练习材料的上传适配器（页面、试卷管理、试卷分析共用同一套批量上传逻辑） */
export const paperUploadAdapter: BatchUploadAdapter = {
  processLabel: '切题',
  async upload(file) {
    const result = await resumeApi.uploadAndAnalyze(file);
    const id = result.resume?.id ?? result.storage?.resumeId;
    if (!id) throw new Error('上传结果缺少试卷 ID，请重试');
    // 历史上重复上传的试卷可能只返回 analysis + storage；有显式状态时以显式状态为准。
    const status = normalizeProcessingStatus(result.resume?.analysisStatus,
      result.analysis ? 'COMPLETED' : 'PENDING');
    return {
      entityId: id,
      duplicate: result.duplicate,
      ...getUploadOutcome(status, result.enqueueAccepted, result.message, '分析'),
    };
  },
  async getStatus(id, signal) {
    const result = await historyApi.getResumeDetail(id, signal);
    return {
      id: result.id,
      status: normalizeProcessingStatus(result.analysisStatus,
        result.analyses?.length ? 'COMPLETED' : 'PENDING'),
      error: result.analysisError,
    };
  },
  retry: id => historyApi.reanalyze(id),
};
