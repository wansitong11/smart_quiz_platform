package interview.guide.modules.interview.skill;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

public final class InterviewSkillProperties {

    private InterviewSkillProperties() {
    }

    /**
     * SKILL.md front matter（标准字段）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillFrontMatterDefinition {
        private String name;
        private String description;
    }

    /**
     * skill.meta.yml（项目自定义字段）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillMetaDefinition {
        private String displayName;
        private DisplayDef display;
        private List<CategoryDef> categories = new ArrayList<>();
        private String examCode;
        private List<QuestionTypeDef> questionTypes = new ArrayList<>();
        private Integer suggestedMinutes;
    }

    /**
     * 运行时聚合结构：标准字段 + 项目自定义字段。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillDefinition {
        private String name;
        private String description;
        private String persona;
        private String displayName;
        private DisplayDef display;
        private List<CategoryDef> categories = new ArrayList<>();
        private String examCode;
        private List<QuestionTypeDef> questionTypes = new ArrayList<>();
        private Integer suggestedMinutes;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DisplayDef {
        private String icon;
        private String gradient;
        private String iconBg;
        private String iconColor;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryDef {
        private String key;
        private String label;
        private String priority;
        private String ref;
        private Boolean shared;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionTypeDef {
        private String type;
        private Integer defaultScore;
        private Integer weight;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExamDefinition {
        private String code;
        private String name;
        private String description;
        private DisplayDef display;
        private Integer sortOrder;
        private Boolean customBucket;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExamListDefinition {
        private List<ExamDefinition> exams = new ArrayList<>();
    }
}
