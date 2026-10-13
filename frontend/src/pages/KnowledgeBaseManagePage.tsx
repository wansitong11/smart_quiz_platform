import {useCallback, useEffect, useState} from 'react';
import {motion} from 'framer-motion';
import {
  AlertCircle,
  CheckCircle,
  ChevronDown,
  Clock,
  Database,
  Download,
  Eye,
  FileText,
  HardDrive,
  Loader2,
  MessageSquare,
  RefreshCw,
  Search,
  Share2,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import {knowledgeBaseApi, KnowledgeBaseItem, KnowledgeBaseStats, SortOption, VectorStatus,} from '../api/knowledgebase';
import type { DataScopeParam } from '../api/scope';
import {kbCategoryApi, type KbCategory} from '../api/kbCategory';
import AdminScopeToggle from '../components/AdminScopeToggle';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import { useAuth } from '../context/AuthContext';
import { canManageKnowledgeBase } from '../utils/knowledgeBasePermission';

interface KnowledgeBaseManagePageProps {
  onUpload: () => void;
  onChat: () => void;
  /** 带分类去上传：上传页会预选该分类 */
  onUploadToCategory?: (categoryCode: string) => void;
}

// 格式化文件大小
function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// 格式化日期
function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// 状态图标组件
function StatusIcon({ status }: { status: VectorStatus }) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle className="w-4 h-4 text-green-500" />;
    case 'PROCESSING':
      return <Loader2 className="w-4 h-4 text-blue-500 animate-spin" />;
    case 'PENDING':
      return <Clock className="w-4 h-4 text-yellow-500" />;
    case 'FAILED':
      return <AlertCircle className="w-4 h-4 text-red-500" />;
    default:
      return <CheckCircle className="w-4 h-4 text-green-500" />;
  }
}

// 状态文本
function getStatusText(status: VectorStatus): string {
  switch (status) {
    case 'COMPLETED':
      return '已完成';
    case 'PROCESSING':
      return '处理中';
    case 'PENDING':
      return '待处理';
    case 'FAILED':
      return '失败';
    default:
      return '未知';
  }
}

// 统计卡片组件
function StatCard({
  icon: Icon,
  label,
  value,
  color,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number;
  color: string;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-slate-700"
    >
      <div className="flex items-center gap-4">
        <div className={`p-3 rounded-lg ${color}`}>
          <Icon className="w-6 h-6 text-white" />
        </div>
        <div>
            <p className="text-sm text-slate-500 dark:text-slate-400">{label}</p>
            <p className="text-2xl font-bold text-slate-800 dark:text-white">{value.toLocaleString()}</p>
        </div>
      </div>
    </motion.div>
  );
}

export default function KnowledgeBaseManagePage({
  onUpload, onChat, onUploadToCategory,
}: KnowledgeBaseManagePageProps) {
  const { hasRole, user } = useAuth();
  const isAdmin = hasRole('ROLE_ADMIN');
  const [stats, setStats] = useState<KnowledgeBaseStats | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [sortBy, setSortBy] = useState<SortOption>('time');
  // 分类筛选：null = 全部，'' = 未分类，其余为分类 code
  const [selectedCategory, setSelectedCategory] = useState<string | null>(null);
  const [selectedVectorStatus, setSelectedVectorStatus] = useState<VectorStatus | ''>('');
  // 数据范围：默认只看自己的；管理员可切换成「查看全部」（后端按角色二次校验）
  const [scope, setScope] = useState<DataScopeParam>(undefined);
  const [categories, setCategories] = useState<KbCategory[]>([]);
  const [newCategoryName, setNewCategoryName] = useState('');
  const [creatingCategory, setCreatingCategory] = useState(false);
  const [deleteItem, setDeleteItem] = useState<KnowledgeBaseItem | null>(null);
  const [deleting, setDeleting] = useState(false);

  // 分类保存状态（行内下拉直接保存，记录正在保存的资料 ID）
  const [savingCategoryId, setSavingCategoryId] = useState<number | null>(null);

  // 重新向量化状态
  const [revectorizing, setRevectorizing] = useState<number | null>(null);

  // 加载数据（不显示loading状态，用于轮询）
  const loadDataSilent = useCallback(async () => {
    try {
      const [statsData, kbList, categoryList] = await Promise.all([
        knowledgeBaseApi.getStatistics(scope),
        searchKeyword
          ? knowledgeBaseApi.search(searchKeyword, scope)
          : selectedCategory === ''
          ? knowledgeBaseApi.getUncategorized(scope)
          : selectedCategory
          ? knowledgeBaseApi.getByCategory(selectedCategory, scope)
          : knowledgeBaseApi.getAllKnowledgeBases(sortBy, selectedVectorStatus || undefined, scope),
        kbCategoryApi.list(),
      ]);
      setStats(statsData);
      setKnowledgeBases(kbList);
      setCategories(categoryList);
    } catch (error) {
      console.error('加载数据失败:', error);
    }
  }, [searchKeyword, sortBy, selectedCategory, selectedVectorStatus, scope]);

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [statsData, kbList, categoryList] = await Promise.all([
        knowledgeBaseApi.getStatistics(scope),
        searchKeyword
          ? knowledgeBaseApi.search(searchKeyword, scope)
          : selectedCategory === ''
          ? knowledgeBaseApi.getUncategorized(scope)
          : selectedCategory
          ? knowledgeBaseApi.getByCategory(selectedCategory, scope)
          : knowledgeBaseApi.getAllKnowledgeBases(sortBy, selectedVectorStatus || undefined, scope),
        kbCategoryApi.list(),
      ]);
      setStats(statsData);
      setKnowledgeBases(kbList);
      setCategories(categoryList);
    } catch (error) {
      console.error('加载数据失败:', error);
    } finally {
      setLoading(false);
    }
  }, [searchKeyword, sortBy, selectedCategory, selectedVectorStatus, scope]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // 轮询：当有 PENDING 或 PROCESSING 状态时，每5秒刷新一次
  useEffect(() => {
    const hasPendingItems = knowledgeBases.some(
      kb => kb.vectorStatus === 'PENDING' || kb.vectorStatus === 'PROCESSING'
    );

    if (hasPendingItems && !loading) {
      const timer = setInterval(() => {
        loadDataSilent();
      }, 5000);

      return () => clearInterval(timer);
    }
  }, [knowledgeBases, loading, loadDataSilent]);

  // 重新向量化
  const handleRevectorize = async (id: number) => {
    try {
      setRevectorizing(id);
      await knowledgeBaseApi.revectorize(id);
      await loadDataSilent();
    } catch (error) {
      console.error('重新向量化失败:', error);
    } finally {
      setRevectorizing(null);
    }
  };

  // 删除知识库
  const handleDelete = async () => {
    if (!deleteItem) return;
    try {
      setDeleting(true);
      await knowledgeBaseApi.deleteKnowledgeBase(deleteItem.id);
      setDeleteItem(null);
      await loadData();
    } catch (error) {
      console.error('删除失败:', error);
    } finally {
      setDeleting(false);
    }
  };

  // 下载知识库
    const handleDownload = async (kb: KnowledgeBaseItem) => {
        try {
            const blob = await knowledgeBaseApi.downloadKnowledgeBase(kb.id);
            const url = window.URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = kb.originalFilename;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            window.URL.revokeObjectURL(url);
        } catch (error) {
            console.error('下载失败:', error);
        }
  };

  /**
   * 行内下拉改分类：选中即保存，先本地更新（立即反馈），失败再回滚。
   */
  const handleCategoryChange = async (kb: KnowledgeBaseItem, nextCategoryCode: string) => {
    const previous = kb.categoryCode ?? '';
    const next = nextCategoryCode || null;
    setSavingCategoryId(kb.id);
    setKnowledgeBases(prev => prev.map(item => item.id === kb.id
      ? {
        ...item,
        categoryCode: next,
        category: categories.find(category => category.code === next)?.name ?? null,
      }
      : item));
    try {
      await knowledgeBaseApi.updateCategory(kb.id, next);
      // 分类计数在服务端，刷新一次保证侧边统计一致
      await loadDataSilent();
    } catch (error) {
      console.error('更新分类失败:', error);
      setKnowledgeBases(prev => prev.map(item => item.id === kb.id
        ? {
          ...item,
          categoryCode: previous || null,
          category: categories.find(category => category.code === previous)?.name ?? null,
        }
        : item));
      alert('修改分类失败，请稍后重试');
    } finally {
      setSavingCategoryId(null);
    }
  };

  // 新建分类
  const handleCreateCategory = async () => {
    const name = newCategoryName.trim();
    if (!name) return;
    try {
      setCreatingCategory(true);
      await kbCategoryApi.create({ name });
      setNewCategoryName('');
      await loadData();
    } catch (error) {
      console.error('新建分类失败:', error);
    } finally {
      setCreatingCategory(false);
    }
  };

  // 删除分类（其下资料落回未分类）
  const handleDeleteCategory = async (category: KbCategory) => {
    if (category.id === null) return;
    try {
      await kbCategoryApi.remove(category.id);
      if (selectedCategory === category.code) {
        setSelectedCategory(null);
      }
      await loadData();
    } catch (error) {
      console.error('删除分类失败:', error);
    }
  };

  // 搜索处理
  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    loadData();
  };

  return (
    <div className="max-w-7xl mx-auto">
      {/* 页面标题 */}
      <div className="flex items-center justify-between mb-8">
        <div>
            <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
            <Database className="w-7 h-7 text-primary-500" />
            知识库管理
          </h1>
            <p className="text-slate-500 dark:text-slate-400 mt-1">管理您的知识库文件，查看使用统计</p>
        </div>
        <div className="flex items-center gap-3">
          {/* 数据范围开关：只有管理员可见（默认也是只看自己的） */}
          {isAdmin && <AdminScopeToggle scope={scope} onChange={setScope} />}
          <button
            onClick={onUpload}
            className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 transition-colors"
          >
            <Upload className="w-4 h-4" />
            上传知识库
          </button>
          <button
            onClick={onChat}
            className="flex items-center gap-2 px-4 py-2 bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
          >
            <MessageSquare className="w-4 h-4" />
            问答助手
          </button>
        </div>
      </div>
      {/* 统计卡片 */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <StatCard
            icon={Database}
            label="知识库总数"
            value={stats.totalCount}
            color="bg-primary-500"
          />
          <StatCard
            icon={MessageSquare}
            label="总提问次数"
            value={stats.totalQuestionCount}
            color="bg-indigo-500"
          />
          <StatCard
            icon={Eye}
            label="总访问次数"
            value={stats.totalAccessCount}
            color="bg-emerald-500"
          />
        </div>
      )}

      {/* 搜索和筛选栏 */}
        <div
            className="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-100 dark:border-slate-700 mb-6">
        <div className="flex flex-wrap items-center gap-4">
          {/* 搜索框 */}
          <form onSubmit={handleSearch} className="flex-1 min-w-[200px]">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchKeyword}
                onChange={(e) => {
                  setSearchKeyword(e.target.value);
                  setSelectedCategory(null);
                  setSelectedVectorStatus('');
                }}
                placeholder="搜索知识库名称..."
                className="w-full pl-10 pr-4 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
              />
            </div>
          </form>

          {/* 排序选择 */}
          <div className="relative">
            <select
              value={sortBy}
              onChange={(e) => {
                setSortBy(e.target.value as SortOption);
                setSearchKeyword('');
                setSelectedCategory(null);
              }}
              className="appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer"
            >
              <option value="time">按时间排序</option>
              <option value="size">按大小排序</option>
              <option value="access">按访问排序</option>
              <option value="question">按提问排序</option>
            </select>
            <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
          </div>

          {/* 分类筛选 */}
          <div className="relative">
            <select
              value={selectedCategory === null ? '__all__' : selectedCategory}
              onChange={(e) => {
                const value = e.target.value;
                setSelectedCategory(value === '__all__' ? null : value);
                setSearchKeyword('');
                setSelectedVectorStatus('');
              }}
              className="appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer"
            >
              <option value="__all__">全部分类</option>
              {categories.map((cat) => (
                <option key={cat.code ?? 'uncategorized'} value={cat.code ?? ''}>
                  {cat.name}（{cat.knowledgeBaseCount}）
                </option>
              ))}
            </select>
            <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
          </div>

          {/* 新建分类 */}
          <div className="flex items-center gap-2">
            <input
              value={newCategoryName}
              onChange={(e) => setNewCategoryName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleCreateCategory();
                }
              }}
              placeholder="新建分类，如 物理"
              className="w-40 px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white text-sm"
            />
            <button
              onClick={handleCreateCategory}
              disabled={creatingCategory || !newCategoryName.trim()}
              className="px-3 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50"
            >
              {creatingCategory ? '新建中...' : '新建分类'}
            </button>
          </div>

          {/* 向量化状态筛选 */}
          <div className="relative">
            <select
              aria-label="向量化状态"
              value={selectedVectorStatus}
              onChange={(e) => {
                setSelectedVectorStatus(e.target.value as VectorStatus | '');
                setSearchKeyword('');
                setSelectedCategory(null);
              }}
              className="appearance-none pl-4 pr-10 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white cursor-pointer"
            >
              <option value="">全部状态</option>
              <option value="PENDING">待处理</option>
              <option value="PROCESSING">处理中</option>
              <option value="COMPLETED">已完成</option>
              <option value="FAILED">失败</option>
            </select>
            <ChevronDown className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 pointer-events-none" />
          </div>
        </div>

        {/* 分类管理：语数英为系统预置（可改名，不可删），自定义分类可删除 */}
        {categories.length > 0 && (
          <div className="flex items-center flex-wrap gap-2 mt-3 text-sm">
            <span className="text-slate-500 dark:text-slate-400">分类：</span>
            {categories.map(category => (
              <span
                key={category.code ?? 'uncategorized'}
                className={`inline-flex items-center gap-1 px-2.5 py-1 rounded-lg text-slate-600 dark:text-slate-300 ${selectedCategory === category.code
                  ? 'bg-primary-50 dark:bg-primary-900/30 ring-1 ring-primary-400'
                  : 'bg-slate-100 dark:bg-slate-700'
                }`}
                title={category.pendingVectorCount > 0
                  ? `${category.pendingVectorCount} 份未完成向量化`
                  : `${category.knowledgeBaseCount} 份资料`}
              >
                {category.name}
                <span className="text-xs text-slate-400">{category.knowledgeBaseCount}</span>
                {onUploadToCategory && category.code && (
                  <button
                    onClick={() => onUploadToCategory(category.code as string)}
                    className="text-slate-400 hover:text-primary-500 transition-colors"
                    title={`上传资料到「${category.name}」`}
                  >
                    <Upload className="w-3 h-3" />
                  </button>
                )}
                {!category.isSystem && category.id !== null && (
                  <button
                    onClick={() => handleDeleteCategory(category)}
                    className="text-slate-400 hover:text-red-500 transition-colors"
                    title="删除分类（资料会落回未分类）"
                  >
                    <X className="w-3 h-3" />
                  </button>
                )}
              </span>
            ))}
          </div>
        )}
      </div>

      {/* 知识库列表 */}
        <div
            className="bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-100 dark:border-slate-700 overflow-hidden">
        {loading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
          </div>
        ) : knowledgeBases.length === 0 ? (
          <div className="text-center py-20">
            <HardDrive className="w-16 h-16 text-slate-300 mx-auto mb-4" />
              <p className="text-slate-500 dark:text-slate-400">暂无知识库</p>
            <button
              onClick={onUpload}
              className="mt-4 text-primary-500 hover:text-primary-600"
            >
              上传第一个知识库
            </button>
          </div>
        ) : (
          <table className="w-full">
              <thead className="bg-slate-50 dark:bg-slate-700 border-b border-slate-100 dark:border-slate-600">
              <tr>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  名称
                </th>
                  {/* 管理员「查看全部」时，同一份文件可能有多人各存一份，这里标出上传人账号以便区分 */}
                  {scope === 'all' && (
                    <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                      上传人
                    </th>
                  )}
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  分类
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  大小
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  状态
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  提问
                </th>
                  <th className="text-left px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  上传时间
                </th>
                  <th className="text-right px-6 py-4 text-sm font-medium text-slate-600 dark:text-slate-300">
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              {knowledgeBases.map((kb, index) => {
                // 能否维护这份资料：共享资料只有管理员可以，私有资料只有归属人（与后端同一套判定）
                const canManage = canManageKnowledgeBase(kb, user);
                return (
                <motion.tr
                  key={kb.id}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: index * 0.05 }}
                  className="border-b border-slate-50 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
                >
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-3">
                      <FileText className="w-5 h-5 text-slate-400" />
                      <div>
                          <div className="flex items-center gap-2">
                            <p className="font-medium text-slate-800 dark:text-white">{kb.name}</p>
                            {kb.visibility === 'SHARED' && (
                              <span
                                className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium bg-amber-50 text-amber-600 dark:bg-amber-900/40 dark:text-amber-300"
                                title="全局共享资料：所有用户都能检索到"
                              >
                                <Share2 className="w-3 h-3" />
                                共享
                              </span>
                            )}
                          </div>
                          <p className="text-xs text-slate-400 dark:text-slate-500">{kb.originalFilename}</p>
                      </div>
                    </div>
                  </td>
                  {scope === 'all' && (
                    <td className="px-6 py-4 text-sm text-slate-600 dark:text-slate-300">
                      {kb.ownerUsername || '-'}
                    </td>
                  )}
                  <td className="px-6 py-4">
                    {canManage ? (
                      /* 分类始终可见，选中即保存，不再需要「悬浮出现铅笔 → 保存」两步 */
                      <div className="flex items-center gap-2">
                        <select
                          value={kb.categoryCode ?? ''}
                          onChange={(e) => handleCategoryChange(kb, e.target.value)}
                          disabled={savingCategoryId === kb.id}
                          title="修改分类（选中即保存）"
                          className={`w-32 px-2 py-1 text-sm border rounded focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 disabled:opacity-60 ${kb.categoryCode
                            ? 'border-slate-200 dark:border-slate-600 text-slate-700 dark:text-slate-200'
                            : 'border-dashed border-slate-300 dark:border-slate-500 text-slate-400 dark:text-slate-500'
                          }`}
                        >
                          <option value="">未分类</option>
                          {categories.filter(item => item.code).map((cat) => (
                            <option key={cat.code} value={cat.code ?? ''}>{cat.name}</option>
                          ))}
                        </select>
                        {savingCategoryId === kb.id && (
                          <Loader2 className="w-4 h-4 animate-spin text-primary-500" />
                        )}
                      </div>
                    ) : (
                      /* 不能维护（别人的共享资料）时只读展示，不给注定 404 的入口 */
                      <span
                        className="text-sm text-slate-500 dark:text-slate-400"
                        title="共享资料只有管理员能改分类"
                      >
                        {kb.category || '未分类'}
                      </span>
                    )}
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-600 dark:text-slate-300">
                    {formatFileSize(kb.fileSize)}
                  </td>
                  <td className="px-6 py-4">
                    <div className="flex items-center gap-2">
                      <StatusIcon status={kb.vectorStatus} />
                        <span className="text-sm text-slate-600 dark:text-slate-300">
                        {getStatusText(kb.vectorStatus)}
                      </span>
                    </div>
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-600 dark:text-slate-300">
                    {kb.questionCount}
                  </td>
                    <td className="px-6 py-4 text-sm text-slate-500 dark:text-slate-400">
                    {formatDate(kb.uploadedAt)}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <div className="flex items-center justify-end gap-1">
                      {/* 下载按钮 */}
                      <button
                        onClick={() => handleDownload(kb)}
                        className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors"
                        title="下载"
                      >
                        <Download className="w-4 h-4" />
                      </button>
                      {/* 已完成或失败任务都允许手动重新向量化（只有可维护的人看得到） */}
                      {canManage && (kb.vectorStatus === 'FAILED' || kb.vectorStatus === 'COMPLETED') && (
                        <button
                          onClick={() => handleRevectorize(kb.id)}
                          disabled={revectorizing === kb.id}
                          className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50"
                          title="重新向量化"
                        >
                          <RefreshCw className={`w-4 h-4 ${revectorizing === kb.id ? 'animate-spin' : ''}`} />
                        </button>
                      )}
                      {/* 删除按钮：只有可维护的人看得到 */}
                      {canManage && (
                        <button
                          onClick={() => setDeleteItem(kb)}
                          className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors"
                          title="删除"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      )}
                      {/* 共享资料但没维护权：说明为什么只有下载可用 */}
                      {!canManage && (
                        <span className="text-xs text-slate-400 dark:text-slate-500 whitespace-nowrap">
                          共享资料仅管理员可管理
                        </span>
                      )}
                    </div>
                  </td>
                </motion.tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem}
        itemType="知识库"
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteItem(null)}
      />
    </div>
  );
}
