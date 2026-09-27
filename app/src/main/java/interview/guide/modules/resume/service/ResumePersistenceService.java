package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.resume.model.PaperAnalysisEntity;
import interview.guide.modules.resume.model.PracticePaperEntity;
import interview.guide.modules.resume.model.ResumeDetailDTO;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 简历持久化服务
 * 简历和评测结果的持久化，简历删除时删除所有关联数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

    private final ResumeRepository resumeRepository;
    private final ResumeAnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final ResumeMapper resumeMapper;
    private final FileHashService fileHashService;
    
    /**
     * 检查简历是否已存在（基于文件内容hash）
     * 
     * @param file 上传的文件
     * @return 如果存在返回已有的简历实体，否则返回空
     */
    public Optional<PracticePaperEntity> findExistingResume(MultipartFile file) {
        try {
            String fileHash = fileHashService.calculateHash(file);
            Optional<PracticePaperEntity> existing = resumeRepository.findByFileHash(fileHash);
            
            if (existing.isPresent()) {
                log.info("检测到重复简历: hash={}", fileHash);
                PracticePaperEntity resume = existing.get();
                resume.incrementAccessCount();
                resumeRepository.save(resume);
            }
            
            return existing;
        } catch (Exception e) {
            log.error("检查简历重复时出错: {}", e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * 保存新简历
     */
    @Transactional(rollbackFor = Exception.class)
    public PracticePaperEntity saveResume(MultipartFile file, String resumeText,
                                   String storageKey, String storageUrl) {
        try {
            String fileHash = fileHashService.calculateHash(file);
            
            PracticePaperEntity resume = new PracticePaperEntity();
            resume.setFileHash(fileHash);
            resume.setOriginalFilename(file.getOriginalFilename());
            resume.setFileSize(file.getSize());
            resume.setContentType(file.getContentType());
            resume.setStorageKey(storageKey);
            resume.setStorageUrl(storageUrl);
            resume.setResumeText(resumeText);
            // 进展时间初始化：NULL 会被 stale 扫描的阈值比较排除
            resume.setAnalysisUpdatedAt(java.time.LocalDateTime.now());

            PracticePaperEntity saved = resumeRepository.save(resume);
            log.info("简历已保存: id={}, hash={}", saved.getId(), fileHash);
            
            return saved;
        } catch (Exception e) {
            log.error("保存简历失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "保存简历失败");
        }
    }
    
    /**
     * 回填简历正文（独立短事务，供 Stream 消费者从 RustFS 恢复文本后使用）
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateResumeText(Long resumeId, String resumeText) {
        PracticePaperEntity resume = resumeRepository.findById(resumeId)
            .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历不存在"));
        resume.setResumeText(resumeText);
        resumeRepository.save(resume);
    }

    /**
     * 保存试卷解析摘要（结构化切题结果概览）。
     */
    @Transactional(rollbackFor = Exception.class)
    public PaperAnalysisEntity saveExtractionSummary(PracticePaperEntity paper,
                                                     PaperExtractionResult result,
                                                     String practiceSessionId) {
        return saveExtractionSummaryInternal(paper, result, practiceSessionId);
    }

    private PaperAnalysisEntity saveExtractionSummaryInternal(PracticePaperEntity paper,
                                                              PaperExtractionResult result,
                                                              String practiceSessionId) {
        try {
            PaperAnalysisEntity entity = new PaperAnalysisEntity();
            entity.setResume(paper);
            entity.setQuestionCount(result.questions().size());
            entity.setTotalScore(result.totalScore());
            entity.setPracticeSessionId(practiceSessionId);
            entity.setSummary(buildExtractionSummary(result));
            entity.setTopicsJson(objectMapper.writeValueAsString(buildTopicStats(result)));
            entity.setWarningsJson(objectMapper.writeValueAsString(result.warnings()));

            PaperAnalysisEntity saved = analysisRepository.save(entity);
            log.info("试卷解析摘要已保存: analysisId={}, paperId={}, questions={}, totalScore={}",
                saved.getId(), paper.getId(), result.questions().size(), result.totalScore());

            return saved;
        } catch (JacksonException e) {
            log.error("序列化试卷解析摘要失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "保存试卷解析摘要失败");
        }
    }

    /**
     * 锁定试卷行并校验执行代次后保存解析摘要，防止超时回收后的旧消费者落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public PaperAnalysisEntity saveExtractionSummaryIfOwned(Long paperId, String attemptId,
                                                            PaperExtractionResult result,
                                                            String practiceSessionId) {
        PracticePaperEntity paper = resumeRepository.findByIdForUpdate(paperId)
            .orElseThrow(() -> new BusinessException(ErrorCode.PAPER_NOT_FOUND, "试卷不存在"));
        if (paper.getAnalysisStatus() != interview.guide.common.model.AsyncTaskStatus.PROCESSING
            || !Objects.equals(paper.getAnalysisAttemptId(), attemptId)) {
            throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "试卷切题任务执行权已失效");
        }
        paper.setAnalysisUpdatedAt(java.time.LocalDateTime.now());
        resumeRepository.save(paper);
        return saveExtractionSummaryInternal(paper, result, practiceSessionId);
    }

    /**
     * 解析摘要文本：题量、题型分布、覆盖板块。
     */
    private String buildExtractionSummary(PaperExtractionResult result) {
        Map<String, Long> typeCounts = result.questions().stream()
            .collect(java.util.stream.Collectors.groupingBy(
                question -> question.questionType() != null ? question.questionType() : "OTHER",
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.counting()));
        String typeText = typeCounts.entrySet().stream()
            .map(e -> e.getKey() + " " + e.getValue() + " 道")
            .collect(java.util.stream.Collectors.joining("、"));
        return "共识别 " + result.questions().size() + " 道题，卷面总分 " + result.totalScore()
            + " 分；题型分布：" + typeText + "。";
    }

    /**
     * 知识板块分布：板块标签 + 题数 + 该板块分值。
     */
    private List<String> buildTopicStats(PaperExtractionResult result) {
        Map<String, int[]> stats = new java.util.LinkedHashMap<>();
        for (InterviewQuestionDTO question : result.questions()) {
            String label = question.category() != null && !question.category().isBlank()
                ? question.category() : "未标注";
            int[] acc = stats.computeIfAbsent(label, k -> new int[2]);
            acc[0]++;
            acc[1] += question.fullScore() != null ? question.fullScore() : 0;
        }
        return stats.entrySet().stream()
            .map(e -> e.getKey() + "|" + e.getValue()[0] + " 题|" + e.getValue()[1] + " 分")
            .toList();
    }
    
    /**
     * 获取简历的最新评测结果
     */
    public Optional<PaperAnalysisEntity> getLatestAnalysis(Long resumeId) {
        return Optional.ofNullable(analysisRepository.findFirstByResumeIdOrderByAnalyzedAtDesc(resumeId));
    }
    
    /**
     * 获取所有简历列表
     */
    public List<PracticePaperEntity> findAllResumes() {
        return resumeRepository.findAll();
    }
    
    /**
     * 获取简历的所有评测记录
     */
    public List<PaperAnalysisEntity> findAnalysesByResumeId(Long resumeId) {
        return analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(resumeId);
    }
    
    /**
     * 将解析摘要实体转换为 DTO（JSON 字段在此处解析）。
     */
    public ResumeDetailDTO.AnalysisHistoryDTO toAnalysisHistoryDTO(PaperAnalysisEntity entity) {
        try {
            List<String> topics = objectMapper.readValue(
                entity.getTopicsJson() != null ? entity.getTopicsJson() : "[]",
                new TypeReference<>() {
                }
            );
            List<String> warnings = objectMapper.readValue(
                entity.getWarningsJson() != null ? entity.getWarningsJson() : "[]",
                new TypeReference<>() {
                }
            );
            return resumeMapper.toAnalysisHistoryDTO(entity, topics, warnings);
        } catch (JacksonException e) {
            log.error("反序列化试卷解析摘要失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.PAPER_EXTRACTION_FAILED, "获取试卷解析结果失败");
        }
    }
    
    /**
     * 根据ID获取简历
     */
    public Optional<PracticePaperEntity> findById(Long id) {
        return resumeRepository.findById(id);
    }
    
    /**
     * 删除简历及其所有关联数据
     * 包括：简历分析记录、面试会话（会自动删除面试答案）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteResume(Long id) {
        Optional<PracticePaperEntity> resumeOpt = resumeRepository.findById(id);
        if (resumeOpt.isEmpty()) {
            throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
        }
        
        PracticePaperEntity resume = resumeOpt.get();
        
        // 1. 删除所有简历分析记录
        List<PaperAnalysisEntity> analyses = analysisRepository.findByResumeIdOrderByAnalyzedAtDesc(id);
        if (!analyses.isEmpty()) {
            analysisRepository.deleteAll(analyses);
            log.info("已删除 {} 条简历分析记录", analyses.size());
        }
        
        // 2. 删除简历实体（面试会话会在服务层删除）
        resumeRepository.delete(resume);
        log.info("简历已删除: id={}, filename={}", id, resume.getOriginalFilename());
    }
}
