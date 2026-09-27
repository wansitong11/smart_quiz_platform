import { request } from './request';

/**
 * 资料分类（语文 / 数学 / 英语或自定义）。
 * code 是系统标识，资料关联与会话检索范围都用它；name 只是展示名，可随时改名。
 */
export interface KbCategory {
  id: number | null;
  code: string | null;
  name: string;
  subjectCode: string | null;
  sortOrder: number;
  isSystem: boolean;
  /** 该分类下的资料数 */
  knowledgeBaseCount: number;
  /** 该分类下未完成向量化的资料数 */
  pendingVectorCount: number;
}

export interface CreateKbCategoryRequest {
  name: string;
  code?: string;
  subjectCode?: string;
  sortOrder?: number;
}

export interface UpdateKbCategoryRequest {
  name?: string;
  subjectCode?: string;
  sortOrder?: number;
}

export const kbCategoryApi = {
  /** 分类列表（含资料数与未向量化数，末尾带「未分类」统计） */
  async list(): Promise<KbCategory[]> {
    return request.get<KbCategory[]>('/api/kb-categories');
  },

  async create(req: CreateKbCategoryRequest): Promise<KbCategory> {
    return request.post<KbCategory>('/api/kb-categories', req);
  },

  async update(id: number, req: UpdateKbCategoryRequest): Promise<KbCategory> {
    return request.put<KbCategory>(`/api/kb-categories/${id}`, req);
  },

  /** 删除分类：其下资料落回「未分类」，系统预置分类不可删 */
  async remove(id: number): Promise<void> {
    return request.delete(`/api/kb-categories/${id}`);
  },
};
