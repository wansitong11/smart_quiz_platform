package interview.guide.modules.resume.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 试卷页：识别阶段的最小状态单元。
 * <p>
 * 识别是"部分成功"的典型场景（第 3 页糊了、其它页正常），
 * 页级状态让重试、人工修正、删除页都只动一行。
 * </p>
 */
@Entity
@Table(name = "paper_pages", uniqueConstraints = {
    @UniqueConstraint(name = "uk_paper_page", columnNames = {"paper_id", "page_no"})
})
@Getter
@Setter
public class PaperPageEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "paper_id", nullable = false)
  private Long paperId;

  @Column(name = "page_no", nullable = false)
  private int pageNo;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  private PageSource source;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private PageStatus status;

  @Column(name = "text_content", columnDefinition = "TEXT")
  private String textContent;

  @Column(name = "has_figure", nullable = false)
  private boolean hasFigure;

  @Column(length = 500)
  private String notes;

  @Column(name = "image_key", length = 500)
  private String imageKey;

  /** 该页资源列表（JSON 快照，便于前端一次拿到）。 */
  @Column(name = "assets_json", columnDefinition = "TEXT")
  private String assetsJson;

  /** 全卷连续行号中的起始行。 */
  @Column(name = "line_start")
  private Integer lineStart;

  @Column(name = "line_count")
  private Integer lineCount;

  /** DOCX 没有物理页，用逻辑页。 */
  @Column(name = "logical_page", nullable = false)
  private boolean logicalPage;

  @Column(length = 128)
  private String model;

  @Column(length = 500)
  private String error;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;
}
