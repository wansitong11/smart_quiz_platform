import type { ReactNode } from 'react';

/**
 * 渲染试卷行内标记：
 * {{字}} = 字下加点；[[u:文字]] = 下划线；[[w:文字]] = 波浪线。
 * 用 React 节点拼接，不用 dangerouslySetInnerHTML。
 */
export default function MarkupText({
  text,
  className,
}: {
  text?: string | null;
  className?: string;
}) {
  if (!text) return null;
  const parts: ReactNode[] = [];
  const pattern = /\{\{([^{}]+)\}\}|\[\[u:([^\]]+)\]\]|\[\[w:([^\]]+)\]\]/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let key = 0;
  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      parts.push(text.slice(lastIndex, match.index));
    }
    if (match[1] !== undefined) {
      parts.push(<span key={key++} className="markup-dot">{match[1]}</span>);
    } else if (match[2] !== undefined) {
      parts.push(<span key={key++} className="markup-underline">{match[2]}</span>);
    } else {
      parts.push(<span key={key++} className="markup-wavy">{match[3]}</span>);
    }
    lastIndex = pattern.lastIndex;
  }
  if (lastIndex < text.length) {
    parts.push(text.slice(lastIndex));
  }
  return <span className={className}>{parts}</span>;
}
