package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.KbCategoryDTO;
import interview.guide.modules.knowledgebase.model.KbCategoryEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KbCategoryRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 资料分类服务。
 * <p>
 * code 是系统标识（资料外键、会话范围都存 code），name 只是展示名；
 * 因此改名不影响已有资料与会话。系统预置分类（语数英）可改名、不可删除。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbCategoryService {

    /** 未分类的展示名（不是真实分类行） */
    public static final String UNCATEGORIZED_NAME = "未分类";

    private final KbCategoryRepository categoryRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /**
     * 分类列表（含资料数与未向量化数），末尾附带「未分类」统计。
     */
    public List<KbCategoryDTO> list() {
        Map<String, Long> totalByCode = toCountMap(knowledgeBaseRepository.countGroupByCategoryCode());
        Map<String, Long> pendingByCode = toCountMap(
            knowledgeBaseRepository.countPendingGroupByCategoryCode(VectorStatus.COMPLETED));

        List<KbCategoryDTO> result = new ArrayList<>();
        for (KbCategoryEntity entity : categoryRepository.findAllByOrderBySortOrderAscIdAsc()) {
            result.add(new KbCategoryDTO(
                entity.getId(),
                entity.getCode(),
                entity.getName(),
                entity.getSubjectCode(),
                entity.getSortOrder(),
                entity.getIsSystem(),
                totalByCode.getOrDefault(entity.getCode(), 0L),
                pendingByCode.getOrDefault(entity.getCode(), 0L)
            ));
        }

        long uncategorized = knowledgeBaseRepository.countByCategoryCodeIsNull();
        if (uncategorized > 0) {
            result.add(KbCategoryDTO.uncategorized(uncategorized,
                knowledgeBaseRepository.countUncategorizedPending(VectorStatus.COMPLETED)));
        }
        return result;
    }

    /**
     * code → 展示名，供资料列表 / 引用出处批量补名使用。
     */
    public Map<String, String> nameByCode() {
        Map<String, String> names = new HashMap<>();
        for (KbCategoryEntity entity : categoryRepository.findAll()) {
            names.put(entity.getCode(), entity.getName());
        }
        return names;
    }

    public Optional<KbCategoryEntity> findByCode(String code) {
        return code == null || code.isBlank() ? Optional.empty() : categoryRepository.findByCode(code);
    }

    public String nameOf(String code) {
        return findByCode(code).map(KbCategoryEntity::getName).orElse(null);
    }

    /**
     * 校验分类存在；code 为空表示「未分类」，直接放行。
     */
    public String normalizeCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.trim();
        if (categoryRepository.findByCode(trimmed).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分类不存在: " + trimmed);
        }
        return trimmed;
    }

    @Transactional(rollbackFor = Exception.class)
    public KbCategoryDTO create(String name, String code, String subjectCode, Integer sortOrder) {
        String normalizedName = requireName(name);
        if (categoryRepository.existsByName(normalizedName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类已存在: " + normalizedName);
        }
        String normalizedCode = code == null || code.isBlank() ? generateCode(normalizedName) : code.trim();
        if (categoryRepository.existsByCode(normalizedCode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类编码已存在: " + normalizedCode);
        }

        KbCategoryEntity entity = new KbCategoryEntity();
        entity.setCode(normalizedCode);
        entity.setName(normalizedName);
        entity.setSubjectCode(trimToNull(subjectCode));
        entity.setSortOrder(sortOrder != null ? sortOrder : nextSortOrder());
        entity.setIsSystem(false);
        entity.setCreatedAt(LocalDateTime.now());

        KbCategoryEntity saved = categoryRepository.save(entity);
        log.info("新建资料分类: code={}, name={}", saved.getCode(), saved.getName());
        return new KbCategoryDTO(saved.getId(), saved.getCode(), saved.getName(), saved.getSubjectCode(),
            saved.getSortOrder(), saved.getIsSystem(), 0, 0);
    }

    /**
     * 改名 / 排序 / 挂学科。code 不允许修改（会话与资料都按 code 关联）。
     */
    @Transactional(rollbackFor = Exception.class)
    public KbCategoryDTO update(Long id, String name, String subjectCode, Integer sortOrder) {
        KbCategoryEntity entity = categoryRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在"));

        if (name != null && !name.isBlank()) {
            String normalizedName = requireName(name);
            if (!normalizedName.equals(entity.getName()) && categoryRepository.existsByName(normalizedName)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "分类已存在: " + normalizedName);
            }
            entity.setName(normalizedName);
        }
        if (subjectCode != null) {
            entity.setSubjectCode(trimToNull(subjectCode));
        }
        if (sortOrder != null) {
            entity.setSortOrder(sortOrder);
        }
        entity.setUpdatedAt(LocalDateTime.now());

        KbCategoryEntity saved = categoryRepository.save(entity);
        log.info("更新资料分类: code={}, name={}, sortOrder={}", saved.getCode(), saved.getName(),
            saved.getSortOrder());
        return new KbCategoryDTO(saved.getId(), saved.getCode(), saved.getName(), saved.getSubjectCode(),
            saved.getSortOrder(), saved.getIsSystem(),
            knowledgeBaseRepository.countByCategoryCode(saved.getCode()), 0);
    }

    /**
     * 删除分类：其下资料落回「未分类」，不删资料。系统预置分类不可删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        KbCategoryEntity entity = categoryRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在"));
        if (Boolean.TRUE.equals(entity.getIsSystem())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "系统预置分类不能删除");
        }
        int migrated = knowledgeBaseRepository.reassignCategory(entity.getCode(), null);
        categoryRepository.delete(entity);
        log.info("删除资料分类: code={}, 资料转未分类={}", entity.getCode(), migrated);
    }

    /**
     * 保证系统预置分类存在（启动时调用，幂等）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void ensureSystemCategories() {
        ensureSystemCategory(KbCategoryEntity.CODE_CHINESE, "语文", 10);
        ensureSystemCategory(KbCategoryEntity.CODE_MATH, "数学", 20);
        ensureSystemCategory(KbCategoryEntity.CODE_ENGLISH, "英语", 30);
    }

    private void ensureSystemCategory(String code, String name, int sortOrder) {
        if (categoryRepository.existsByCode(code)) {
            return;
        }
        KbCategoryEntity entity = new KbCategoryEntity();
        entity.setCode(code);
        entity.setName(name);
        entity.setSubjectCode(code);
        entity.setSortOrder(sortOrder);
        entity.setIsSystem(true);
        entity.setCreatedAt(LocalDateTime.now());
        categoryRepository.save(entity);
        log.info("初始化系统资料分类: code={}, name={}", code, name);
    }

    private int nextSortOrder() {
        return categoryRepository.findAllByOrderBySortOrderAscIdAsc().stream()
            .map(KbCategoryEntity::getSortOrder)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .map(max -> max + 10)
            .orElse(100);
    }

    private String requireName(String name) {
        String trimmed = trimToNull(name);
        if (trimmed == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类名称不能为空");
        }
        if (trimmed.length() > 64) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分类名称过长");
        }
        return trimmed;
    }

    /** 名称转 code：保留字母数字，中文等非 ASCII 名称回退为 cat-<时间戳> */
    private String generateCode(String name) {
        String slug = name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-)|(-$)", "");
        if (slug.isBlank()) {
            slug = "cat-" + System.currentTimeMillis();
        }
        String code = slug.length() > 56 ? slug.substring(0, 56) : slug;
        return categoryRepository.existsByCode(code) ? code + "-" + System.currentTimeMillis() % 10000 : code;
    }

    private Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) {
                continue;
            }
            counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
