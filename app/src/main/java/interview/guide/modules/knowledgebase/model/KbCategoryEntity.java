package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 资料分类实体。
 * <p>
 * 分类是资料的「学科/用途」划分（语文、数学、英语或自定义），
 * code 给系统用（会话范围、外键都存 code），name 给界面看，改名不影响历史数据。
 * </p>
 */
@Entity
@Table(name = "kb_categories", indexes = {
    @Index(name = "idx_kb_category_code", columnList = "code", unique = true),
    @Index(name = "idx_kb_category_sort", columnList = "sortOrder")
})
public class KbCategoryEntity {

    /** 系统预置分类（语数英），不可删除 */
    public static final String CODE_CHINESE = "chinese";
    public static final String CODE_MATH = "math";
    public static final String CODE_ENGLISH = "english";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 分类编码（会话范围与资料外键都存这个值）
    @Column(nullable = false, length = 64, unique = true)
    private String code;

    // 展示名
    @Column(nullable = false, length = 64)
    private String name;

    // 关联的平台学科 key（chinese / math / english），自定义分类可为空
    @Column(length = 64)
    private String subjectCode;

    // 排序值，越小越靠前
    @Column(nullable = false)
    private Integer sortOrder = 0;

    // 是否系统预置：可改名，不可删除
    @Column(nullable = false)
    private Boolean isSystem = false;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubjectCode() {
        return subjectCode;
    }

    public void setSubjectCode(String subjectCode) {
        this.subjectCode = subjectCode;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getIsSystem() {
        return isSystem;
    }

    public void setIsSystem(Boolean isSystem) {
        this.isSystem = isSystem;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
