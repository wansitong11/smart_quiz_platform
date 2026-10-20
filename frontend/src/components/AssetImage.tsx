import { useEffect, useState } from 'react';
import { ImageOff, Loader2 } from 'lucide-react';
import { paperRecognitionApi } from '../api/paperRecognition';

/**
 * 鉴权资源图片：<img src> 不会带 Authorization，必须先取 Blob。
 */
export default function AssetImage({
  paperId,
  assetId,
  alt,
  className,
}: {
  paperId?: number | null;
  assetId?: string | null;
  alt: string;
  className?: string;
}) {
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!paperId || !assetId) return;
    let cancelled = false;
    let objectUrl: string | null = null;
    setFailed(false);
    paperRecognitionApi.fetchAsset(paperId, assetId)
      .then(blob => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [paperId, assetId]);

  if (!paperId || !assetId) {
    return <span className="text-xs text-slate-400">图形选项</span>;
  }
  if (failed) {
    return (
      <span className="inline-flex items-center gap-1 text-xs text-slate-400">
        <ImageOff className="h-4 w-4" />
        图片加载失败
      </span>
    );
  }
  if (!url) {
    return (
      <span className="inline-flex h-16 w-24 items-center justify-center rounded bg-slate-50 dark:bg-slate-900">
        <Loader2 className="h-4 w-4 animate-spin text-slate-400" />
      </span>
    );
  }
  return <img src={url} alt={alt} className={className ?? 'max-h-24 object-contain'} />;
}
