package interview.guide.modules.resume.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 试卷 / 练习材料解析摘要实体。
 * <p>
 * 记录一次结构化切题的结果概览：题目数量、卷面总分、知识板块分布、解析告警，
 * 以及本次切题生成的那次练习会话 ID。逐题明细存放在练习会话的题目与作答记录中，
 * 本表只保留概览，便于列表页与详情页展示。
 * </p>
 */
@Entity
@Table(name = "paper_analyses")
public class PaperAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 关联的试卷 / 练习材料
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_id", nullable = false)
    private PracticePaperEntity resume;

    // 题目数量
    private Integer questionCount;

    // 卷面总分（各题分值之和）
    private Integer totalScore;

    // 切题生成的练习会话 ID，用于跳转作答
    @Column(name = "practice_session_id", length = 36)
    private String practiceSessionId;

    // 解析摘要（题量、题型分布、识别质量等）
    @Column(columnDefinition = "TEXT")
    private String summary;

    // 知识板块分布 (JSON 数组，元素为「板块 key|标签|题数」)
    @Column(columnDefinition = "TEXT")
    private String topicsJson;

    // 解析告警 (JSON 数组，如缺参考答案、分值为推断值、含图形未识别等)
    @Column(columnDefinition = "TEXT")
    private String warningsJson;

    // 解析时间
    @Column(nullable = false)
    private LocalDateTime analyzedAt;

    @PrePersist
    protected void onCreate() {
        analyzedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PracticePaperEntity getResume() {
        return resume;
    }

    public void setResume(PracticePaperEntity resume) {
        this.resume = resume;
    }

    public Integer getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }

    public Integer getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(Integer totalScore) {
        this.totalScore = totalScore;
    }

    public String getPracticeSessionId() {
        return practiceSessionId;
    }

    public void setPracticeSessionId(String practiceSessionId) {
        this.practiceSessionId = practiceSessionId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getTopicsJson() {
        return topicsJson;
    }

    public void setTopicsJson(String topicsJson) {
        this.topicsJson = topicsJson;
    }

    public String getWarningsJson() {
        return warningsJson;
    }

    public void setWarningsJson(String warningsJson) {
        this.warningsJson = warningsJson;
    }

    public LocalDateTime getAnalyzedAt() {
        return analyzedAt;
    }

    public void setAnalyzedAt(LocalDateTime analyzedAt) {
        this.analyzedAt = analyzedAt;
    }
}
