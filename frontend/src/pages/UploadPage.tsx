import BatchUploadPage from '../components/batch-upload/BatchUploadPage';
import PaperUploadGroup from '../components/paper-recognition/PaperUploadGroup';
import { paperUploadAdapter } from '../api/batchUpload';
import { PAPER_FILE_POLICY } from '../utils/batchUpload';

/**
 * 上传入口：
 * - 试卷分析页（embedded）只渲染「单份试卷上传组」，图片与文字文件混选合并为一份试卷；
 * - 试卷管理页（/upload）保留批量上传，一份文件 = 一份试卷，逐个异步切题。
 *
 * @param embedded   嵌在其它页面里时用（去掉外层留白，标题由宿主页面提供）
 * @param onOpenPaper 切题完成后跳到试卷分析页开始作答（如 `/papers?paper=12`），不传则不显示该入口
 */
export default function UploadPage({ onBack, embedded = false, onOpenPaper }: {
  onBack: () => void;
  embedded?: boolean;
  onOpenPaper?: (paperId: number) => void;
}) {
  if (embedded) {
    return (
      <div className="mx-auto max-w-5xl px-0 pt-2">
        {onOpenPaper && <PaperUploadGroup onUploaded={onOpenPaper} />}
      </div>
    );
  }

  return (
    <BatchUploadPage
      entityLabel="试卷"
      policy={PAPER_FILE_POLICY}
      adapter={paperUploadAdapter}
      backLabel="返回试卷管理"
      onBack={onBack}
      onOpenProcessed={onOpenPaper}
      openLabel="开始作答"
    />
  );
}
