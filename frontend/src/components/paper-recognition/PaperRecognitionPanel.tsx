import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertCircle,
  CheckCircle2,
  Eye,
  ImageOff,
  Loader2,
  RotateCcw,
  Save,
  ScanText,
  Trash2,
} from 'lucide-react';
import { paperRecognitionApi } from '../../api/paperRecognition';
import type { PaperPage, PaperRecognition } from '../../types/paperRecognition';
import type { StructureNode, StructurePreview } from '../../types/paperRecognition';

const POLL_INTERVAL_MS = 2500;

/**
 * 识别进度 + 逐页校对。
 * <p>
 * 识别中是进度视图；识别结束后切到"左页图 / 右文本"的校对视图，
 * 用户可以改文字、重做单页、删页，确认后才进入切题。
 * </p>
 */
export default function PaperRecognitionPanel({
  paperId,
  onConfirmed,
}: {
  paperId: number;
  onConfirmed: () => void;
}) {
  const [data, setData] = useState<PaperRecognition | null>(null);
  const [selectedPage, setSelectedPage] = useState(1);
  const [drafts, setDrafts] = useState<Record<number, string>>({});
  const [dirty, setDirty] = useState<Record<number, boolean>>({});
  const [thumbs, setThumbs] = useState<Record<number, string>>({});
  const [assetUrls, setAssetUrls] = useState<Record<string, string>>({});
  const [structure, setStructure] = useState<StructurePreview | null>(null);
  const [structureLoading, setStructureLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');

  const dirtyRef = useRef(dirty);
  dirtyRef.current = dirty;
  const thumbsRef = useRef<Record<number, string>>({});
  const loadedThumbs = useRef<Set<string>>(new Set());
  const assetRefs = useRef<Record<string, string>>({});

  const load = useCallback(async () => {
    const next = await paperRecognitionApi.get(paperId);
    setData(next);
    setDrafts(prev => {
      const merged = { ...prev };
      next.pages.forEach(page => {
        if (!dirtyRef.current[page.pageNo]) {
          merged[page.pageNo] = page.text ?? '';
        }
      });
      return merged;
    });
    setSelectedPage(current =>
      next.pages.some(page => page.pageNo === current) ? current : next.pages[0]?.pageNo ?? 1,
    );
  }, [paperId]);

  useEffect(() => {
    load().catch(err => setError(err instanceof Error ? err.message : '加载识别结果失败'));
  }, [load]);

  // 识别中轮询；校对/失败后停止
  useEffect(() => {
    if (!data) return;
    if (data.recognitionStatus !== 'PENDING' && data.recognitionStatus !== 'PROCESSING') {
      return;
    }
    const timer = window.setInterval(() => {
      load().catch(() => undefined);
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [data, load]);

  // 页图缩略图（鉴权请求 → Blob URL）
  useEffect(() => {
    if (!data) return;
    let cancelled = false;
    data.pages.forEach(page => {
      const key = `${paperId}:${page.pageNo}`;
      if (loadedThumbs.current.has(key)) return;
      loadedThumbs.current.add(key);
      paperRecognitionApi.fetchPageImage(paperId, page.pageNo)
        .then(blob => {
          if (cancelled) {
            // 数据轮询会重建 effect；让下一轮重新拉，避免缩略图永久缺失
            loadedThumbs.current.delete(key);
            return;
          }
          const url = URL.createObjectURL(blob);
          thumbsRef.current = { ...thumbsRef.current, [page.pageNo]: url };
          setThumbs({ ...thumbsRef.current });
        })
        .catch(() => {
          loadedThumbs.current.delete(key);
        });
    });
    return () => {
      cancelled = true;
    };
  }, [paperId, data]);

  useEffect(() => () => {
    Object.values(thumbsRef.current).forEach(url => URL.revokeObjectURL(url));
    thumbsRef.current = {};
    Object.values(assetRefs.current).forEach(url => URL.revokeObjectURL(url));
    assetRefs.current = {};
  }, []);

  // 页面资源（图形选项 / 题干图 / 表格）缩略图
  useEffect(() => {
    if (!data) return;
    data.assets?.forEach(asset => {
      if (assetRefs.current[asset.key]) return;
      paperRecognitionApi.fetchAsset(paperId, asset.key)
        .then(blob => {
          const url = URL.createObjectURL(blob);
          assetRefs.current = { ...assetRefs.current, [asset.key]: url };
          setAssetUrls({ ...assetRefs.current });
        })
        .catch(() => undefined);
    });
  }, [paperId, data]);

  const pages = data?.pages ?? [];
  const currentPage = useMemo(
    () => pages.find(page => page.pageNo === selectedPage) ?? pages[0],
    [pages, selectedPage],
  );
  const readyCount = pages.filter(page => page.status === 'READY').length;
  const failedCount = pages.filter(page => page.status === 'FAILED').length;
  const recognizing = data?.recognitionStatus === 'PENDING' || data?.recognitionStatus === 'PROCESSING';

  const updateDraft = (pageNo: number, text: string) => {
    setDrafts(prev => ({ ...prev, [pageNo]: text }));
    setDirty(prev => ({ ...prev, [pageNo]: true }));
  };

  const savePage = async (page: PaperPage) => {
    setBusy(`save-${page.pageNo}`);
    setError('');
    try {
      await paperRecognitionApi.updatePageText(paperId, page.pageNo, drafts[page.pageNo] ?? '');
      setDirty(prev => ({ ...prev, [page.pageNo]: false }));
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败');
    } finally {
      setBusy(null);
    }
  };

  const retryPage = async (page: PaperPage, forceVision: boolean) => {
    setBusy(`retry-${page.pageNo}`);
    setError('');
    try {
      await paperRecognitionApi.retryPage(paperId, page.pageNo, forceVision);
      setDirty(prev => ({ ...prev, [page.pageNo]: false }));
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '重试失败');
    } finally {
      setBusy(null);
    }
  };

  const resetThumbs = () => {
    Object.values(thumbsRef.current).forEach(url => URL.revokeObjectURL(url));
    thumbsRef.current = {};
    loadedThumbs.current.clear();
    setThumbs({});
  };

  const deletePage = async (page: PaperPage) => {
    if (pages.length <= 1) {
      setError('至少要保留 1 页');
      return;
    }
    if (!window.confirm(`确定删除第 ${page.pageNo} 页吗？后面的页会依次前移。`)) return;
    setBusy(`delete-${page.pageNo}`);
    setError('');
    try {
      await paperRecognitionApi.deletePage(paperId, page.pageNo);
      setDirty(prev => {
        const next = { ...prev };
        delete next[page.pageNo];
        return next;
      });
      // 删页后页码会前移，旧缩略图的 pageNo 映射已经失效
      resetThumbs();
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败');
    } finally {
      setBusy(null);
    }
  };

  const retryAllFailed = async () => {
    setBusy('retry-all');
    setError('');
    try {
      await paperRecognitionApi.retryRecognition(paperId, false);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : '重试失败');
    } finally {
      setBusy(null);
    }
  };

  const previewStructure = async () => {
    setStructureLoading(true);
    setError('');
    try {
      setStructure(await paperRecognitionApi.previewStructure(paperId));
    } catch (err) {
      setError(err instanceof Error ? err.message : '结构预览失败');
    } finally {
      setStructureLoading(false);
    }
  };

  const confirm = async () => {
    if (!data) return;
    const targets = pages.filter(page => dirty[page.pageNo]);
    setBusy('confirm');
    setError('');
    try {
      for (const page of targets) {
        await paperRecognitionApi.updatePageText(paperId, page.pageNo, drafts[page.pageNo] ?? '');
      }
      const ignoreFailed = failedCount > 0
        ? window.confirm(`还有 ${failedCount} 页识别失败，忽略它们继续切题吗？`)
        : false;
      if (failedCount > 0 && !ignoreFailed) {
        setBusy(null);
        return;
      }
      await paperRecognitionApi.confirm(paperId, ignoreFailed);
      onConfirmed();
    } catch (err) {
      setError(err instanceof Error ? err.message : '确认失败');
    } finally {
      setBusy(null);
    }
  };

  if (!data) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="flex items-start gap-3 rounded-xl border border-red-200 bg-red-50 p-4 dark:border-red-800 dark:bg-red-900/30">
          <AlertCircle className="mt-0.5 h-5 w-5 flex-shrink-0 text-red-500" />
          <p className="text-sm text-red-600 dark:text-red-400">{error}</p>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
        <div>
          <p className="text-sm font-semibold text-slate-900 dark:text-white">
            {recognizing ? 'AI 正在逐页识别' : data.recognitionStatus === 'FAILED' ? '识别失败' : '识别完成，请校对'}
          </p>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            共 {pages.length} 页，已完成 {readyCount} 页
            {failedCount > 0 ? `，失败 ${failedCount} 页` : ''}
            {data.questionNos.length > 0 ? `；检测到题号 ${data.questionNos[0]}–${data.questionNos[data.questionNos.length - 1]}` : ''}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {failedCount > 0 && (
            <button
              type="button"
              onClick={retryAllFailed}
              disabled={busy !== null}
              className="inline-flex items-center gap-2 rounded-lg border border-amber-300 px-3 py-2 text-sm text-amber-700 hover:bg-amber-50 disabled:opacity-60 dark:border-amber-700 dark:text-amber-300 dark:hover:bg-amber-900/20"
            >
              {busy === 'retry-all' ? <Loader2 className="h-4 w-4 animate-spin" /> : <RotateCcw className="h-4 w-4" />}
              重试失败页
            </button>
          )}
          {!recognizing && (
            <button
              type="button"
              onClick={previewStructure}
              disabled={busy !== null || structureLoading}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
            >
              {structureLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : <ScanText className="h-4 w-4" />}
              预览结构
            </button>
          )}
          {!recognizing && (
            <button
              type="button"
              onClick={confirm}
              disabled={busy !== null || readyCount === 0}
              className="inline-flex items-center gap-2 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-60"
            >
              {busy === 'confirm' ? <Loader2 className="h-4 w-4 animate-spin" /> : <CheckCircle2 className="h-4 w-4" />}
              确认并切题
            </button>
          )}
        </div>
      </div>

      {recognizing && (
        <div className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
          <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
            {pages.map(page => (
              <li key={page.pageNo} className="flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2 text-sm dark:bg-slate-900/40">
                {page.status === 'READY' ? (
                  <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                ) : page.status === 'FAILED' ? (
                  <AlertCircle className="h-4 w-4 text-red-500" />
                ) : page.status === 'PROCESSING' ? (
                  <Loader2 className="h-4 w-4 animate-spin text-primary-500" />
                ) : (
                  <ScanText className="h-4 w-4 text-slate-400" />
                )}
                <span className="text-slate-700 dark:text-slate-200">第 {page.pageNo} 页</span>
                <span className="ml-auto text-xs text-slate-400">
                  {page.status === 'READY' ? '已完成' : page.status === 'FAILED' ? '失败' : '识别中'}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {(data.assets?.length ?? 0) > 0 && (
        <div className="rounded-2xl border border-slate-200 bg-white p-3 dark:border-slate-700 dark:bg-slate-800">
          <p className="mb-2 text-xs font-medium text-slate-500 dark:text-slate-400">
            页面资源（图形选项 / 题干图 / 表格）
          </p>
          <div className="flex gap-3 overflow-x-auto pb-1">
            {data.assets.map(asset => (
              <a
                key={asset.key}
                href={assetUrls[asset.key]}
                target="_blank"
                rel="noreferrer"
                className="flex w-28 flex-shrink-0 flex-col gap-1 rounded-lg border border-slate-200 p-2 text-[11px] text-slate-500 hover:bg-slate-50 dark:border-slate-600 dark:text-slate-400 dark:hover:bg-slate-700"
              >
                {assetUrls[asset.key] ? (
                  <img src={assetUrls[asset.key]} alt={asset.key} className="h-16 w-full object-contain" />
                ) : (
                  <span className="flex h-16 items-center justify-center">
                    <Loader2 className="h-4 w-4 animate-spin text-slate-400" />
                  </span>
                )}
                <span className="truncate">{asset.kind} · 第 {asset.pageNo ?? '?'} 页</span>
              </a>
            ))}
          </div>
        </div>
      )}

      {structure && (
        <div className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
            <p className="text-sm font-semibold text-slate-900 dark:text-white">
              结构预览：{structure.questionCount} 题 / {structure.totalScore} 分
            </p>
            <button
              type="button"
              onClick={() => setStructure(null)}
              className="text-xs text-slate-400 hover:text-slate-600"
            >
              收起
            </button>
          </div>
          <StructureTree nodes={structure.nodes} depth={0} />
          {structure.warnings.length > 0 && (
            <ul className="mt-2 text-xs text-amber-600 dark:text-amber-400">
              {structure.warnings.map(warning => <li key={warning}>· {warning}</li>)}
            </ul>
          )}
        </div>
      )}

      {!recognizing && currentPage && (
        <div className="grid gap-4 lg:grid-cols-[180px_1fr]">
          <aside className="space-y-2">
            {pages.map(page => (
              <button
                key={page.pageNo}
                type="button"
                onClick={() => setSelectedPage(page.pageNo)}
                className={`flex w-full items-center gap-2 rounded-xl border p-2 text-left text-sm transition ${
                  page.pageNo === currentPage.pageNo
                    ? 'border-primary-400 bg-primary-50 dark:border-primary-600 dark:bg-primary-900/20'
                    : 'border-slate-200 bg-white hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:hover:bg-slate-700'
                }`}
              >
                <span className="flex h-14 w-11 flex-shrink-0 items-center justify-center overflow-hidden rounded bg-slate-100 dark:bg-slate-900">
                  {thumbs[page.pageNo]
                    ? <img src={thumbs[page.pageNo]} alt={`第 ${page.pageNo} 页`} className="h-full w-full object-cover" />
                    : <ImageOff className="h-4 w-4 text-slate-400" />}
                </span>
                <span className="min-w-0">
                  <span className="block text-slate-800 dark:text-slate-100">第 {page.pageNo} 页</span>
                  <span className={`text-xs ${
                    page.status === 'FAILED' ? 'text-red-500' : 'text-slate-400'
                  }`}>
                    {page.source === 'TEXT' ? '文字层' : '识图'}
                    {page.hasFigure ? ' · 含图' : ''}
                  </span>
                </span>
              </button>
            ))}
          </aside>

          <section className="rounded-2xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-800">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <p className="text-sm font-semibold text-slate-900 dark:text-white">
                第 {currentPage.pageNo} 页
                <span className="ml-2 text-xs font-normal text-slate-500 dark:text-slate-400">
                  只识别印刷体，手写作答与批改痕迹请忽略
                </span>
              </p>
              <div className="flex flex-wrap gap-2">
                {thumbs[currentPage.pageNo] && (
                  <a
                    href={thumbs[currentPage.pageNo]}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs text-slate-600 hover:bg-slate-50 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
                  >
                    <Eye className="h-3.5 w-3.5" />
                    查看原页
                  </a>
                )}
                <button
                  type="button"
                  onClick={() => retryPage(currentPage, true)}
                  disabled={busy !== null}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 px-3 py-1.5 text-xs text-slate-600 hover:bg-slate-50 disabled:opacity-60 dark:border-slate-600 dark:text-slate-300 dark:hover:bg-slate-700"
                >
                  {busy === `retry-${currentPage.pageNo}` ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
                  用识图重做
                </button>
                <button
                  type="button"
                  onClick={() => deletePage(currentPage)}
                  disabled={busy !== null}
                  className="inline-flex items-center gap-1.5 rounded-lg border border-red-200 px-3 py-1.5 text-xs text-red-600 hover:bg-red-50 disabled:opacity-60 dark:border-red-800 dark:text-red-400 dark:hover:bg-red-900/20"
                >
                  {busy === `delete-${currentPage.pageNo}` ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Trash2 className="h-3.5 w-3.5" />}
                  删除本页
                </button>
              </div>
            </div>

            {(currentPage.error || currentPage.notes) && (
              <div className="mb-3 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-700 dark:bg-amber-900/20 dark:text-amber-300">
                {currentPage.error || currentPage.notes}
              </div>
            )}

            <textarea
              value={drafts[currentPage.pageNo] ?? ''}
              onChange={event => updateDraft(currentPage.pageNo, event.target.value)}
              rows={18}
              className="w-full resize-y rounded-xl border border-slate-200 bg-slate-50 p-3 font-mono text-sm leading-relaxed text-slate-800 focus:border-primary-400 focus:outline-none dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100"
              placeholder="这一页的转写文本"
            />

            <div className="mt-3 flex justify-end">
              <button
                type="button"
                onClick={() => savePage(currentPage)}
                disabled={busy !== null || !dirty[currentPage.pageNo]}
                className="inline-flex items-center gap-2 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-40 dark:bg-slate-100 dark:text-slate-900"
              >
                {busy === `save-${currentPage.pageNo}` ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
                保存本页
              </button>
            </div>
          </section>
        </div>
      )}

      {data.warnings.length > 0 && (
        <ul className="rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-700 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-300">
          {data.warnings.map(warning => <li key={warning}>· {warning}</li>)}
        </ul>
      )}
    </div>
  );
}

function StructureTree({ nodes, depth }: { nodes: StructureNode[]; depth: number }) {
  return (
    <ul className={depth === 0
      ? 'space-y-1'
      : 'mt-1 ml-3 space-y-1 border-l border-slate-200 pl-3 dark:border-slate-700'}>
      {nodes.map((node, index) => (
        <li key={`${depth}-${index}-${node.type}-${node.title ?? ''}`}>
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className={`rounded px-1.5 py-0.5 font-medium ${structureTypeClass(node.type)}`}>
              {structureTypeLabel(node.type)}
            </span>
            <span className="text-slate-700 dark:text-slate-200">
              {node.title || node.text?.split('\n')[0]?.slice(0, 70) || '—'}
            </span>
            {node.fullScore != null && (
              <span className="text-amber-600 dark:text-amber-400">{node.fullScore} 分</span>
            )}
            {node.pageFrom != null && (
              <span className="text-slate-400">
                第 {node.pageFrom}
                {node.pageTo != null && node.pageTo !== node.pageFrom ? `–${node.pageTo}` : ''} 页
              </span>
            )}
            {node.assetIds.length > 0 && (
              <span className="text-indigo-500 dark:text-indigo-400">{node.assetIds.length} 张图</span>
            )}
          </div>
          {node.children.length > 0 && <StructureTree nodes={node.children} depth={depth + 1} />}
        </li>
      ))}
    </ul>
  );
}

function structureTypeLabel(type: string): string {
  switch (type) {
    case 'SECTION': return '大题';
    case 'MATERIAL': return '材料';
    case 'QUESTION': return '题';
    case 'INSTRUCTION': return '说明';
    default: return type;
  }
}

function structureTypeClass(type: string): string {
  switch (type) {
    case 'SECTION': return 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-300';
    case 'MATERIAL': return 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300';
    case 'QUESTION': return 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-200';
    default: return 'bg-amber-50 text-amber-600 dark:bg-amber-900/30 dark:text-amber-300';
  }
}
