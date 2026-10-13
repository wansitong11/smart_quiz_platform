package interview.guide.modules.interviewschedule.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 学习计划 / 考试排期实体。
 * <p>
 * 当前列仍沿用原项目的面试排期字段（company_name / position / interview_time 等），
 * 待前端「练习安排」页面改造时一并调整为学科、考试名称、考试时间等教育语义。
 * </p>
 */
@Entity
@Table(name = "study_plans")
@Data
public class StudyPlanEntity {

    /** 归属用户：学习计划按归属人隔离 */
    @Column(name = "owner_id")
    private Long ownerId;
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    @Column(nullable = false)
    private String position;

    @Column(name = "interview_time", nullable = false)
    private LocalDateTime interviewTime;

    @Column(name = "interview_type")
    private String interviewType; // ONSITE, VIDEO, PHONE

    @Column(name = "meeting_link", columnDefinition = "TEXT")
    private String meetingLink;

    @Column(name = "round_number")
    private Integer roundNumber = 1;

    private String interviewer;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterviewStatus status = InterviewStatus.PENDING;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
