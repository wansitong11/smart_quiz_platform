import { useRef, useState } from 'react';
import { AlertCircle, Loader2, Upload, X } from 'lucide-react';
import { paperRecognitionApi } from '../../api/paperRecognition';

const MAX_FILES = 10;
const MAX_SIZE = 50 * 1024 * 1024;
const ALLOWED_EXTENSIONS = ['pdf', 'doc', 'docx', 'txt', 'md', 'jpg', 'jpeg', 'png'];

/**
 * 统一试卷上传组：一次可选多个文件（图片 / PDF / Word / 纯文字），按顺序合并为同一份试卷。
 * 后端自动判定「纯文字直接切题」或「需要识图校对」，上传后统一交给宿主页面恢复现场。
 */
export default function PaperUploadGroup({
  onUploaded,
}: {
  onUploaded: (paperId: number) => void;
}) {
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  const addFiles = (selected: FileList | null) => {
    if (!selected) return;
    setError('');
    const next = [...files];
    Array.from(selected).forEach(file => {
      const extension = file.name.includes('.')
        ? file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase()
        : '';
      if (!ALLOWED_EXTENSIONS.includes(extension)) {
        setError(`${file.name}：仅支持 PDF、Word、TXT、MD、JPG、PNG`);
        return;
      }
      if (file.size > MAX_SIZE) {
        setError(`${file.name}：超过 50MB，请压缩后再上传`);
        return;
      }
      if (next.some(item => item.name === file.name && item.size === file.size)) {
        return;
      }
      if (next.length >= MAX_FILES) {
        setError(`一次最多 ${MAX_FILES} 个文件`);
        return;
      }
      next.push(file);
    });
    setFiles(next);
  };

  const removeFile = (index: number) => {
    setFiles(prev => prev.filter((_, i) => i !== index));
  };

  const upload = async () => {
    if (files.length === 0) return;
    setBusy(true);
    setError('');
    try {
      const result = await paperRecognitionApi.upload(files);
      setFiles([]);
      onUploaded(result.paperId);
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="mb-8 rounded-2xl border border-dashed border-emerald-300 bg-emerald-50/60 p-5 dark:border-emerald-700 dark:bg-emerald-900/20">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
            <Upload className="h-4 w-4 text-emerald-600" />
            上传试卷 / 习题
          </h2>
          <p className="mt-1 text-sm text-slate-600 dark:text-slate-300">
            图片、PDF、Word 或纯文字文件都可混选，最多 10 个，按选择顺序合并成一份试卷；
            纯文字直接切题，需要识图的先预览校对。
          </p>
        </div>
        <button
          type="button"
          onClick={() => inputRef.current?.click()}
          disabled={busy}
          className="inline-flex items-center gap-2 rounded-xl border border-emerald-300 bg-white px-4 py-2 text-sm font-medium text-emerald-700 transition hover:bg-emerald-50 disabled:opacity-60 dark:border-emerald-700 dark:bg-slate-800 dark:text-emerald-300"
        >
          <Upload className="h-4 w-4" />
          选择文件
        </button>
        <input
          ref={inputRef}
          type="file"
          accept=".pdf,.doc,.docx,.txt,.md,.jpg,.jpeg,.png,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,image/jpeg,image/png"
          multiple
          className="hidden"
          onChange={event => {
            addFiles(event.target.files);
            event.target.value = '';
          }}
        />
      </div>

      {files.length > 0 && (
        <ul className="mt-4 space-y-2">
          {files.map((file, index) => (
            <li
              key={`${file.name}-${file.size}-${index}`}
              className="flex items-center justify-between rounded-lg bg-white px-3 py-2 text-sm dark:bg-slate-800"
            >
              <span className="truncate text-slate-700 dark:text-slate-200">
                {index + 1}. {file.name}
              </span>
              <button
                type="button"
                onClick={() => removeFile(index)}
                className="ml-3 text-slate-400 hover:text-red-500"
                aria-label="移除"
              >
                <X className="h-4 w-4" />
              </button>
            </li>
          ))}
        </ul>
      )}

      {error && (
        <p className="mt-3 flex items-center gap-2 text-sm text-red-600 dark:text-red-400">
          <AlertCircle className="h-4 w-4" />
          {error}
        </p>
      )}

      {files.length > 0 && (
        <button
          type="button"
          onClick={upload}
          disabled={busy}
          className="mt-4 inline-flex items-center gap-2 rounded-xl bg-emerald-600 px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-emerald-700 disabled:opacity-60"
        >
          {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
          {busy ? '正在上传…' : `上传 ${files.length} 个文件`}
        </button>
      )}
    </section>
  );
}
