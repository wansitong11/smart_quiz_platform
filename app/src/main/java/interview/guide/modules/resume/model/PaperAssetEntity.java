package interview.guide.modules.resume.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 页面资源：图形选项、题干图、表格截图。
 * <p>
 * 与文本行分开存储：文本靠 lineNo 定位，资源靠 assetKey 被结构树引用。
 * </p>
 */
@Entity
@Table(name = "paper_assets", uniqueConstraints = {
    @UniqueConstraint(name = "uk_paper_asset", columnNames = {"paper_id", "asset_key"})
})
@Getter
@Setter
public class PaperAssetEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "paper_id", nullable = false)
  private Long paperId;

  @Column(name = "page_no")
  private Integer pageNo;

  @Column(name = "asset_key", nullable = false, length = 64)
  private String assetKey;

  @Column(name = "parent_key", length = 64)
  private String parentKey;

  @Column(nullable = false, length = 24)
  private String kind;

  @Column(length = 16)
  private String role;

  @Column(name = "storage_key", length = 500)
  private String storageKey;

  /** 归一化坐标 [x,y,w,h]，JSON 数组。 */
  @Column(name = "rect_json", length = 200)
  private String rectJson;

  @Column(name = "anchor_text", length = 300)
  private String anchorText;

  @Column(name = "anchor_line")
  private Integer anchorLine;

  @Column(length = 16)
  private String layout;

  @Column(name = "option_count")
  private Integer optionCount;

  /** 归一化切线，逗号分隔。 */
  @Column(name = "cut_offsets", length = 500)
  private String cutOffsets;

  private Integer width;
  private Integer height;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt = LocalDateTime.now();
}
