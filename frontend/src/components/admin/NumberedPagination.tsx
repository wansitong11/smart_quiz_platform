import { ChevronLeft, ChevronRight } from 'lucide-react';

interface NumberedPaginationProps {
  /** 0-based current page */
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
  className?: string;
}

type PageToken = number | 'ellipsis';

function pageTokens(current: number, total: number): PageToken[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, index) => index + 1);
  }
  const visible = new Set<number>([1, total]);
  for (const page of [current - 1, current, current + 1]) {
    if (page >= 1 && page <= total) {
      visible.add(page);
    }
  }
  const sorted = [...visible].sort((left, right) => left - right);
  const tokens: PageToken[] = [];
  let previous = 0;
  for (const page of sorted) {
    if (page - previous > 1) {
      tokens.push('ellipsis');
    }
    tokens.push(page);
    previous = page;
  }
  return tokens;
}

export default function NumberedPagination({
  page,
  totalPages,
  onChange,
  className = '',
}: NumberedPaginationProps) {
  const current = page + 1;
  const safeTotal = Math.max(1, totalPages);
  const tokens = pageTokens(current, safeTotal);

  return (
    <div className={`flex flex-wrap items-center justify-between gap-3 text-sm ${className}`}>
      <span className="text-slate-400">第 {current} / {safeTotal} 页</span>
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => onChange(page - 1)}
          disabled={page <= 0}
          aria-label="上一页"
          className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-slate-600 transition hover:bg-slate-50 disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-700"
        >
          <ChevronLeft className="h-4 w-4" />
        </button>
        {tokens.map((token, index) => (
          token === 'ellipsis' ? (
            <span key={`ellipsis-${index}`} className="px-1 text-slate-400">…</span>
          ) : (
            <button
              key={token}
              type="button"
              onClick={() => onChange(token - 1)}
              aria-current={token === current ? 'page' : undefined}
              className={`inline-flex h-8 min-w-8 items-center justify-center rounded-lg border px-2 text-xs transition ${
                token === current
                  ? 'border-primary-500 bg-primary-500 text-white'
                  : 'border-slate-200 text-slate-600 hover:bg-slate-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-700'
              }`}
            >
              {token}
            </button>
          )
        ))}
        <button
          type="button"
          onClick={() => onChange(page + 1)}
          disabled={page >= safeTotal - 1}
          aria-label="下一页"
          className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 text-slate-600 transition hover:bg-slate-50 disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-700"
        >
          <ChevronRight className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
