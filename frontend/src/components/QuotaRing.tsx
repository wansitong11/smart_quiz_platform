import { useMemo } from 'react';

/**
 * 用户名旁的额度圆环：显示当月累计百分比。
 * 已显示的进度不会因总量或用户数变化而回退，只在后续模型调用时继续增长。
 * 授权/管理员不展示圆环，由外层直接显示“不限额度”。
 */
export default function QuotaRing({
  percent,
  size = 36,
}: {
  percent: number;
  size?: number;
}) {
  const stroke = 3.5;
  const { radius, circumference } = useMemo(() => {
    const r = (size - stroke) / 2;
    return { radius: r, circumference: 2 * Math.PI * r };
  }, [size]);
  const clamped = Math.max(0, Math.min(100, Number(percent) || 0));
  const offset = circumference * (1 - clamped / 100);
  const danger = clamped >= 100;
  const ringClass = danger ? 'stroke-rose-500' : 'stroke-primary-500';
  const displayPercent = Number(clamped.toFixed(2));

  return (
    <div
      className="flex shrink-0 flex-col items-center justify-center"
      title={`已用 ${clamped}%`}
    >
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="-rotate-90">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          className="stroke-slate-200 dark:stroke-slate-700"
        />
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          className={ringClass}
        />
      </svg>
      <span
        className={`mt-1 whitespace-nowrap text-[12px] font-bold leading-none tabular-nums ${
          danger ? 'text-rose-500' : 'text-slate-500 dark:text-slate-400'
        }`}
      >
        {`${displayPercent}% / 100%`}
      </span>
    </div>
  );
}
