package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 试卷 / 练习材料实体：保存上传的试卷、练习册、讲义等资料原件与解析后的文本，
 * 以文件哈希去重。解析报告见 {@link PaperAnalysisEntity}。
 */
@Entity
@Table(name = "practice_papers", indexes = {
    @Index(name = "idx_practice_paper_hash", columnList = "fileHash", unique = true)
})
public class PracticePaperEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    // 文件内容的SHA-256哈希值，用于去重
    @Column(nullable = false, unique = true, length = 64)
    private String fileHash;
    
    // 原始文件名
    @Column(nullable = false)
    private String originalFilename;
    
    // 文件大小（字节）
    private Long fileSize;
    
    // 文件类型
    private String contentType;
    
    // RustFS存储的文件Key
    @Column(length = 500)
    private String storageKey;
    
    // RustFS存储的文件URL
    @Column(length = 1000)
    private String storageUrl;
    
    // 解析后的资料文本
    @Column(columnDefinition = "TEXT")
    private String resumeText;
    
    // 上传时间
    @Column(nullable = false)
    private LocalDateTime uploadedAt;
    
    // 最后访问时间
    private LocalDateTime lastAccessedAt;
    
    // 访问次数
    private Integer accessCount = 0;

    // 分析状态（新上传时为 PENDING，异步分析完成后变为 COMPLETED）
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AsyncTaskStatus analysisStatus = AsyncTaskStatus.PENDING;

    /**
     * 最后一次有效进展时间（状态变化与心跳都推进），用于卡住任务恢复判定。
     */
    @jakarta.persistence.Column(name = "analysis_updated_at")
    private java.time.LocalDateTime analysisUpdatedAt;

    /**
     * 恢复调度器自动补投次数（手动重试清零），与 Stream retryCount 独立。
     */
    @jakarta.persistence.Column(name = "analysis_recovery_count", nullable = false)
    private int analysisRecoveryCount = 0;

    /**
     * 当前 PROCESSING 执行代次；条件心跳和终态写入必须同时匹配该值。
     */
    @Column(name = "analysis_attempt_id", length = 36)
    private String analysisAttemptId;

    // 分析错误信息（失败时记录）
    @Column(length = 500)
    private String analysisError;
    
    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        accessCount = 1;
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }
    
    public String getOriginalFilename() {
        return originalFilename;
    }
    
    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }
    
    public Long getFileSize() {
        return fileSize;
    }
    
    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public String getStorageKey() {
        return storageKey;
    }
    
    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }
    
    public String getStorageUrl() {
        return storageUrl;
    }
    
    public void setStorageUrl(String storageUrl) {
        this.storageUrl = storageUrl;
    }
    
    public String getResumeText() {
        return resumeText;
    }
    
    public void setResumeText(String resumeText) {
        this.resumeText = resumeText;
    }
    
    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }
    
    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
    
    public LocalDateTime getLastAccessedAt() {
        return lastAccessedAt;
    }
    
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }
    
    public Integer getAccessCount() {
        return accessCount;
    }
    
    public void setAccessCount(Integer accessCount) {
        this.accessCount = accessCount;
    }
    
    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }

    public AsyncTaskStatus getAnalysisStatus() {
        return analysisStatus;
    }

    public java.time.LocalDateTime getAnalysisUpdatedAt() {
        return analysisUpdatedAt;
    }

    public void setAnalysisUpdatedAt(java.time.LocalDateTime analysisUpdatedAt) {
        this.analysisUpdatedAt = analysisUpdatedAt;
    }

    public int getAnalysisRecoveryCount() {
        return analysisRecoveryCount;
    }

    public void setAnalysisRecoveryCount(int analysisRecoveryCount) {
        this.analysisRecoveryCount = analysisRecoveryCount;
    }

    public String getAnalysisAttemptId() {
        return analysisAttemptId;
    }

    public void setAnalysisAttemptId(String analysisAttemptId) {
        this.analysisAttemptId = analysisAttemptId;
    }

    public void setAnalysisStatus(AsyncTaskStatus analysisStatus) {
        this.analysisStatus = analysisStatus;
    }

    public String getAnalysisError() {
        return analysisError;
    }

    public void setAnalysisError(String analysisError) {
        this.analysisError = analysisError;
    }
}
