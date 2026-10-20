import BatchUploadPage from '../components/batch-upload/BatchUploadPage';
import ScanPaperUpload from '../components/paper-recognition/ScanPaperUpload';
import { paperUploadAdapter } from '../api/batchUpload';
import { PAPER_FILE_POLICY } from '../utils/batchUpload';

/**
 * 统一的上传界面：试卷管理、试卷分析（以及以后的其它入口）都用这一个页面，
 * 逻辑走批量上传（多选、并发 2 个、逐个异步切题、可重试），文案统一是"试卷"。
 *
 * @param embedded   嵌在其它页面里时用（去掉外层留白，标题由宿主页面提供）
 * @param onOpenPaper 切题完成后跳到试卷分析页开始作答（如 `/papers?paper=12`），不传则不显示该入口
 */
export default function UploadPage({ onBack, embedded = false, onOpenPaper }: {
  onBack: () => void;
  embedded?: boolean;
  onOpenPaper?: (paperId: number) => void;
}) {
  return (
    <div>
      {onOpenPaper && (
        <div className="mx-auto max-w-5xl px-0 pt-2">
          <ScanPaperUpload onUploaded={onOpenPaper} />
        </div>
      )}
      <BatchUploadPage
        entityLabel="试卷"
        policy={PAPER_FILE_POLICY}
        adapter={paperUploadAdapter}
        backLabel="返回试卷管理"
        onBack={onBack}
        embedded={embedded}
        onOpenProcessed={onOpenPaper}
        openLabel="开始作答"
      />
    </div>
  );
}
