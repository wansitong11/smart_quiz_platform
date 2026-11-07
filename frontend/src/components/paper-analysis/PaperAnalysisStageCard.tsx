import type { ReactNode } from 'react';

export function PaperAnalysisStageCard({ icon, title, description, action }: {
  icon: ReactNode;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="bg-white dark:bg-slate-800 rounded-2xl p-12 text-center">
      <div className="w-16 h-16 mx-auto mb-6 bg-primary-500/10 rounded-full flex items-center justify-center">
        {icon}
      </div>
      <h3 className="text-lg font-semibold text-slate-800 dark:text-slate-200 mb-2">{title}</h3>
      <p className="text-sm text-slate-500 dark:text-slate-400">{description}</p>
      {action && <div className="mt-5 flex justify-center">{action}</div>}
    </div>
  );
}
