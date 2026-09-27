import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { FolderOpen } from 'lucide-react';
import BatchUploadPage from '../components/batch-upload/BatchUploadPage';
import { createKnowledgeBaseUploadAdapter } from '../api/batchUpload';
import { kbCategoryApi, type KbCategory } from '../api/kbCategory';
import { KNOWLEDGE_BASE_FILE_POLICY } from '../utils/batchUpload';

/**
 * 资料批量上传页。
 * <p>
 * 先选「导入到哪个分类」，再导入实际文件：整批文件都归到该分类，
 * 不选则归入「未分类」（上传后仍可在管理页调整）。
 * 支持深链 `/knowledgebase/upload?categoryCode=math` 预选分类。
 * </p>
 */
export default function KnowledgeBaseUploadPage({ onBack }: { onBack: () => void }) {
  const [searchParams] = useSearchParams();
  const [categories, setCategories] = useState<KbCategory[]>([]);
  const [categoryCode, setCategoryCode] = useState<string>(searchParams.get('categoryCode') ?? '');

  // 适配器只在上传那一刻读取分类，所以用 ref 支撑 getter，保证适配器引用稳定
  const categoryCodeRef = useRef(categoryCode);
  categoryCodeRef.current = categoryCode;
  const adapter = useMemo(
    () => createKnowledgeBaseUploadAdapter(() => categoryCodeRef.current || undefined),
    [],
  );

  useEffect(() => {
    kbCategoryApi.list()
      .then(list => setCategories(list.filter(item => item.code)))
      .catch(err => console.error('加载资料分类失败', err));
  }, []);

  const selectedCategory = categories.find(item => item.code === categoryCode);

  return <BatchUploadPage
    entityLabel="知识库"
    policy={KNOWLEDGE_BASE_FILE_POLICY}
    adapter={adapter}
    customNamePlaceholder="知识库名称（可选，默认使用文件名）"
    backLabel="返回知识库"
    onBack={onBack}
    contextSlot={
      <div className="mb-6 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-600 dark:bg-slate-800">
        <div className="flex flex-wrap items-center gap-3">
          <span className="inline-flex items-center gap-2 text-sm font-medium text-slate-700 dark:text-slate-200">
            <FolderOpen className="h-4 w-4 text-primary-500" />
            导入到分类
          </span>
          <select
            value={categoryCode}
            onChange={(e) => setCategoryCode(e.target.value)}
            className="rounded-lg border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-slate-600 dark:bg-slate-700 dark:text-white"
          >
            <option value="">未分类</option>
            {categories.map(category => (
              <option key={category.code} value={category.code ?? ''}>
                {category.name}（{category.knowledgeBaseCount} 份）
              </option>
            ))}
          </select>
          <span className="text-xs text-slate-500 dark:text-slate-400">
            {selectedCategory
              ? `本批文件都会归到「${selectedCategory.name}」，上传后仍可单独调整`
              : '不选则先归入「未分类」，上传后在知识库管理页可以再分类'}
          </span>
        </div>
      </div>
    }
  />;
}
