package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.auth.security.OwnerScope;
import interview.guide.modules.auth.service.UserDirectoryService;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity.MessageType;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库查询服务
 * 负责知识库列表和详情的查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final OwnerScope ownerScope;
    private final RagChatMessageRepository ragChatMessageRepository;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final FileStorageService fileStorageService;
    private final KbCategoryService categoryService;
    private final UserDirectoryService userDirectoryService;
    private final KnowledgeBaseAccessService accessService;

    /**
     * 获取知识库列表（支持状态过滤和排序）
     * 
     * @param vectorStatus 向量化状态，null 表示不过滤
     * @param sortBy 排序字段，null 或 "time" 表示按时间排序
     * @return 知识库列表
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(VectorStatus vectorStatus, String sortBy) {
        List<KnowledgeBaseEntity> entities;
        
        // 如果指定了状态，按状态过滤
        if (vectorStatus != null) {
            entities = knowledgeBaseRepository.findByVectorStatusOrderByUploadedAtDesc(vectorStatus);
        } else {
            // 否则获取所有知识库
            entities = knowledgeBaseRepository.findAllByOrderByUploadedAtDesc();
        }
        
        // 如果指定了排序字段，在内存中排序
        if (sortBy != null && !sortBy.isBlank() && !sortBy.equalsIgnoreCase("time")) {
            entities = sortEntities(entities, sortBy);
        }
        
        return toDtoList(entities);
    }

    /**
     * 获取所有知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases() {
        return listKnowledgeBases(null, null);
    }

    /**
     * 按向量化状态获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBasesByStatus(VectorStatus vectorStatus) {
        return listKnowledgeBases(vectorStatus, null);
    }

    /**
     * 根据ID获取知识库详情
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        return knowledgeBaseRepository.findByIdAccessible(id, ownerScope.effectiveOwnerId())
            .map(entity -> knowledgeBaseMapper.toListItemDTO(entity, categoryService.nameByCode(),
                userDirectoryService.usernamesByIds(java.util.List.of(entity.getOwnerId()))));
    }

    /**
     * 根据ID列表获取知识库名称列表
     */
    public List<String> getKnowledgeBaseNames(List<Long> ids) {
        return ids.stream()
            .map(id -> knowledgeBaseRepository.findByIdAccessible(id, ownerScope.effectiveOwnerId())
                .map(KnowledgeBaseEntity::getName)
                .orElse("未知知识库"))
            .toList();
    }

    // ========== 分类管理 ==========

    /**
     * 根据分类 code 获取知识库列表（code 为空表示「未分类」）
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String categoryCode) {
        List<KnowledgeBaseEntity> entities;
        if (categoryCode == null || categoryCode.isBlank()) {
            entities = knowledgeBaseRepository.findByCategoryCodeIsNullOrderByUploadedAtDesc();
        } else {
            entities = knowledgeBaseRepository.findByCategoryCodeOrderByUploadedAtDesc(categoryCode.trim());
        }
        return toDtoList(entities);
    }

    /**
     * 更新知识库分类（传 categoryCode，null 表示未分类）
     */
    @Transactional
    public void updateCategory(Long id, String categoryCode) {
        // 改分类是写操作：自己的资料，或（共享资料 + 管理员）
        KnowledgeBaseEntity entity = accessService.requireManageable(id);
        entity.setCategoryCode(categoryService.normalizeCode(categoryCode));
        knowledgeBaseRepository.save(entity);
        log.info("更新知识库分类: id={}, categoryCode={}", id, categoryCode);
    }

    // ========== 搜索功能 ==========

    /**
     * 按关键词搜索知识库
     */
    public List<KnowledgeBaseListItemDTO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return listKnowledgeBases();
        }
        return knowledgeBaseMapper.toListItemDTOList(
            knowledgeBaseRepository.searchByKeywordScoped(keyword.trim(), ownerScope.effectiveOwnerId()),
            categoryService.nameByCode()
        );
    }

    /**
     * 列表统一补分类展示名与上传人账号（各查一次映射，避免逐条查库）。
     */
    private List<KnowledgeBaseListItemDTO> toDtoList(List<KnowledgeBaseEntity> entities) {
        // 资料列表统一在这里收口：自己的 + 全局共享（管理员 scope=all 时不过滤）
        List<KnowledgeBaseEntity> visible = entities.stream()
            .filter(entity -> ownerScope.canReadKnowledgeBase(entity.getOwnerId(), entity.getVisibility()))
            .toList();
        // 归属人账号：管理员「查看全部」时同一份文件可能有多个人上传，列表要能区分是谁传的
        Map<Long, String> ownerNames = userDirectoryService.usernamesByIds(
            visible.stream().map(KnowledgeBaseEntity::getOwnerId).toList());
        return knowledgeBaseMapper.toListItemDTOList(visible, categoryService.nameByCode(), ownerNames);
    }

    // ========== 排序功能 ==========

    /**
     * 按指定字段排序获取知识库列表（保持向后兼容）
     */
    public List<KnowledgeBaseListItemDTO> listSorted(String sortBy) {
        return listKnowledgeBases(null, sortBy);
    }

    /**
     * 在内存中对实体列表排序
     */
    private List<KnowledgeBaseEntity> sortEntities(List<KnowledgeBaseEntity> entities, String sortBy) {
        return switch (sortBy.toLowerCase()) {
            case "size" -> entities.stream()
                .sorted((a, b) -> Long.compare(b.getFileSize(), a.getFileSize()))
                .toList();
            case "access" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getAccessCount(), a.getAccessCount()))
                .toList();
            case "question" -> entities.stream()
                .sorted((a, b) -> Integer.compare(b.getQuestionCount(), a.getQuestionCount()))
                .toList();
            default -> entities; // time 已经在数据库层面排序了
        };
    }

    // ========== 统计功能 ==========

    /**
     * 获取知识库统计信息
     * 总提问次数从用户消息数统计，确保多知识库提问只算一次
     * 统计口径与列表一致：自己的 + 全局共享；管理员 scope=all 时统计全部
     */
    public KnowledgeBaseStatsDTO getStatistics() {
        Long ownerId = ownerScope.effectiveOwnerId();
        return new KnowledgeBaseStatsDTO(
            knowledgeBaseRepository.countAccessible(ownerId),
            ragChatMessageRepository.countByTypeScoped(MessageType.USER, ownerId),  // 真正的提问次数
            knowledgeBaseRepository.sumAccessCountScoped(ownerId),
            knowledgeBaseRepository.countByVectorStatusAccessible(ownerId, VectorStatus.COMPLETED),
            knowledgeBaseRepository.countByVectorStatusAccessible(ownerId, VectorStatus.PROCESSING)
        );
    }

    // ========== 下载功能 ==========

    /**
     * 下载知识库文件
     */
    public byte[] downloadFile(Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseRepository
            .findByIdAccessible(id, ownerScope.effectiveOwnerId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在或无权访问"));

        String storageKey = entity.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        log.info("下载知识库文件: id={}, filename={}", id, entity.getOriginalFilename());
        return fileStorageService.downloadFile(storageKey);
    }

    /**
     * 获取知识库文件信息（用于下载）
     */
    public KnowledgeBaseEntity getEntityForDownload(Long id) {
        return knowledgeBaseRepository
            .findByIdAccessible(id, ownerScope.effectiveOwnerId())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在或无权访问"));
    }
}
