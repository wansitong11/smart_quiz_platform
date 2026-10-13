package interview.guide.modules.knowledgebase.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "knowledge_base_questions", indexes = {
    @Index(name = "idx_kb_question_kb_status", columnList = "knowledge_base_id,status"),
    @Index(name = "idx_kb_question_skill_difficulty", columnList = "skill_id,difficulty")
})
public class KnowledgeBaseQuestionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "knowledge_base_id", nullable = false)
  private KnowledgeBaseEntity knowledgeBase;

  @Column(name = "knowledge_base_id", insertable = false, updatable = false)
  private Long knowledgeBaseId;

  // skill_id 列非空，资料库自动生成的题目固定写入这个默认值
  public static final String DEFAULT_SKILL_ID = "knowledge-base";

  @Column(name = "skill_id", nullable = false, length = 64)
  private String skillId = DEFAULT_SKILL_ID;

  @Column(length = 16)
  private String difficulty;

  @Column(length = 64)
  private String type;

  // 知识板块，由模型生成（例如"现代文阅读"/"数列"/"语法填空"），用于筛选与组卷
  @Column(length = 64)
  private String category;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String question;

  @Column(length = 300)
  private String topicSummary;

  @Column(columnDefinition = "TEXT")
  private String referenceAnswer;

  @Column(columnDefinition = "TEXT")
  private String keyPointsJson;

  @Column(columnDefinition = "TEXT")
  private String scoringRubric;

  @Column(columnDefinition = "TEXT")
  private String followUpsJson;

    // ===== 考卷题字段（模拟测验同构）=====
    // 题型：SINGLE_CHOICE / MULTI_CHOICE / FILL_BLANK / SHORT_ANSWER / READING / WRITING / OTHER
    @Column(name = "question_type", length = 32)
    private String questionType;

    // 选择题选项，形如 [{"key":"A","text":"..."}]
    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    // 该题分值
    @Column(name = "full_score")
    private Integer fullScore;

    // 题目解析（作答后展示）
    @Column(columnDefinition = "TEXT")
    private String analysis;

  @Column(columnDefinition = "TEXT")
  private String sourceContext;

  @Column(length = 64)
  private String kbContentHash;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private KnowledgeBaseQuestionStatus status = KnowledgeBaseQuestionStatus.DRAFT;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public KnowledgeBaseEntity getKnowledgeBase() {
    return knowledgeBase;
  }

  public void setKnowledgeBase(KnowledgeBaseEntity knowledgeBase) {
    this.knowledgeBase = knowledgeBase;
  }

  public Long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public String getSkillId() {
    return skillId;
  }

  public void setSkillId(String skillId) {
    this.skillId = skillId;
  }

  public String getDifficulty() {
    return difficulty;
  }

  public void setDifficulty(String difficulty) {
    this.difficulty = difficulty;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getQuestion() {
    return question;
  }

  public void setQuestion(String question) {
    this.question = question;
  }

  public String getTopicSummary() {
    return topicSummary;
  }

  public void setTopicSummary(String topicSummary) {
    this.topicSummary = topicSummary;
  }

  public String getReferenceAnswer() {
    return referenceAnswer;
  }

  public void setReferenceAnswer(String referenceAnswer) {
    this.referenceAnswer = referenceAnswer;
  }

  public String getKeyPointsJson() {
    return keyPointsJson;
  }

  public void setKeyPointsJson(String keyPointsJson) {
    this.keyPointsJson = keyPointsJson;
  }

  public String getScoringRubric() {
    return scoringRubric;
  }

  public void setScoringRubric(String scoringRubric) {
    this.scoringRubric = scoringRubric;
  }

  public String getFollowUpsJson() {
    return followUpsJson;
  }

  public void setFollowUpsJson(String followUpsJson) {
    this.followUpsJson = followUpsJson;
  }

  public String getQuestionType() {
    return questionType;
  }

  public void setQuestionType(String questionType) {
    this.questionType = questionType;
  }

  public String getOptionsJson() {
    return optionsJson;
  }

  public void setOptionsJson(String optionsJson) {
    this.optionsJson = optionsJson;
  }

  public Integer getFullScore() {
    return fullScore;
  }

  public void setFullScore(Integer fullScore) {
    this.fullScore = fullScore;
  }

  public String getAnalysis() {
    return analysis;
  }

  public void setAnalysis(String analysis) {
    this.analysis = analysis;
  }

  public String getSourceContext() {
    return sourceContext;
  }

  public void setSourceContext(String sourceContext) {
    this.sourceContext = sourceContext;
  }

  public String getKbContentHash() {
    return kbContentHash;
  }

  public void setKbContentHash(String kbContentHash) {
    this.kbContentHash = kbContentHash;
  }

  public KnowledgeBaseQuestionStatus getStatus() {
    return status;
  }

  public void setStatus(KnowledgeBaseQuestionStatus status) {
    this.status = status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}
