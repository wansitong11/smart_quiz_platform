package interview.guide.modules.resume.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 一份试卷的原始输入文件：解析异步化后，消费者据此从对象存储恢复原料。
 */
@Entity
@Table(name = "paper_input_files")
@Getter
@Setter
public class PaperInputFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "paper_id", nullable = false)
    private Long paperId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "filename", length = 500)
    private String filename;

    @Column(name = "content_type", length = 200)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;
}
