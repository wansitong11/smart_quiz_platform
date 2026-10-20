import type { ReactNode } from 'react';
import MarkupText from './MarkupText';

/**
 * 试卷文本渲染：
 * - Markdown 表格渲染成真正的 <table>（避免 | 和列宽错位）
 * - 其它行保留换行
 * - 行内 {{加点}} / [[u:下划线]] / [[w:波浪线]] 继续由 MarkupText 处理
 */
export default function RichPaperText({
  text,
  className,
}: {
  text?: string | null;
  className?: string;
}) {
  if (!text) return null;
  const blocks: ReactNode[] = [];
  const lines = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').split('\n');
  let index = 0;
  let key = 0;

  while (index < lines.length) {
    if (isTableStart(lines, index)) {
      const tableLines: string[] = [];
      while (index < lines.length && lines[index].trim().startsWith('|')) {
        tableLines.push(lines[index]);
        index++;
      }
      blocks.push(<MarkdownTable key={`t-${key++}`} lines={tableLines} />);
      continue;
    }
    const line = lines[index];
    blocks.push(
      <div key={`l-${key++}`} className="whitespace-pre-wrap">
        {line ? <MarkupText text={line} /> : <span>&nbsp;</span>}
      </div>
    );
    index++;
  }

  return <div className={className}>{blocks}</div>;
}

function isTableStart(lines: string[], index: number): boolean {
  if (index + 1 >= lines.length) return false;
  const first = lines[index].trim();
  const second = lines[index + 1].trim();
  if (!first.startsWith('|') || !second.startsWith('|')) return false;
  const separator = second.replace(/\|/g, '').replace(/[\s:-]/g, '');
  return separator === '' && second.includes('-');
}

function MarkdownTable({ lines }: { lines: string[] }) {
  if (lines.length < 2) {
    return <div className="whitespace-pre-wrap">{lines.join('\n')}</div>;
  }
  const header = splitRow(lines[0]);
  const rows = lines.slice(2).map(splitRow).filter(row => row.length > 0);
  return (
    <div className="my-3 overflow-x-auto">
      <table className="min-w-full border-collapse text-sm">
        <thead>
          <tr className="bg-slate-100 dark:bg-slate-700">
            {header.map((cell, i) => (
              <th
                key={i}
                className="border border-slate-300 px-3 py-2 text-left align-top font-semibold text-slate-700 dark:border-slate-600 dark:text-slate-200"
              >
                <MarkupText text={cell} />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex} className="even:bg-slate-50 dark:even:bg-slate-800/40">
              {header.map((_, cellIndex) => (
                <td
                  key={cellIndex}
                  className="border border-slate-300 px-3 py-2 align-top whitespace-pre-wrap text-slate-700 dark:border-slate-600 dark:text-slate-300"
                >
                  <MarkupText text={row[cellIndex] ?? ''} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function splitRow(line: string): string[] {
  let value = line.trim();
  if (value.startsWith('|')) value = value.slice(1);
  if (value.endsWith('|')) value = value.slice(0, -1);
  return value.split('|').map(cell => cell.trim());
}
