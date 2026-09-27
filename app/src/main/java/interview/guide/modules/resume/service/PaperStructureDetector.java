package interview.guide.modules.resume.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 试卷结构识别（规则优先）。
 * <p>
 * 从整卷文本里切出「大题 → 小节 → 材料 + 题目区间」的骨架，供切题按小节分组进行。
 * 识别不出来时退化为「整卷 → 一个大题」的单层结构，不影响既有流程。
 * </p>
 */
@Component
public class PaperStructureDetector {

    /** 一级大题：行首「一、」「二、」 */
    private static final Pattern MAJOR_HEADING =
        Pattern.compile("^\\s*([一二三四五六七八九十]{1,3})\\s*[、.．]\\s*(\\S.*)$");

    /** 二级小节：行首「（一）」「(二)」 */
    private static final Pattern MINOR_HEADING =
        Pattern.compile("^\\s*[（(]\\s*([一二三四五六七八九十]{1,3})\\s*[）)]\\s*(\\S.*)$");

    /** 题号：行首「1.」「2、」 */
    private static final Pattern QUESTION_NO =
        Pattern.compile("^\\s*(\\d{1,3})\\s*[.．、)）]\\s*");

    /** 材料引导语 */
    private static final Pattern MATERIAL_MARKER =
        Pattern.compile("^\\s*(材料[一二三四五六七八九十\\d]{1,3}[：:]?|阅读下面的(文字|材料|短文)[，,：:]?.*)$");

    /** 材料块的最小长度，避免把说明性短句当材料 */
    private static final int MIN_MATERIAL_CHARS = 100;

    /**
     * 结构骨架。
     *
     * @param sections 顶层大题
     */
    public record PaperStructure(List<Section> sections) {

        public List<Section> allLeafGroups() {
            List<Section> leaves = new ArrayList<>();
            for (Section section : sections) {
                section.collectLeafGroups(leaves);
            }
            return leaves;
        }
    }

    /**
     * 一个大题或小节。
     *
     * @param title        标题（如「一、现代文阅读」）
     * @param subtitle     副标题（如「（一）现代文阅读 I（本题共 5 小题，19 分）」）
     * @param level        层级：1 = 大题，2 = 小节
     * @param materialText 该节点下的材料原文（可能为空）
     * @param text         该节点的完整文本切片（材料 + 题目），用于切题
     * @param questionNos  该节点内的题号
     * @param children     子节点
     */
    public record Section(String title, String subtitle, int level, String materialText, String text,
                          List<Integer> questionNos, List<Section> children) {

        boolean isLeafGroup() {
            return children == null || children.isEmpty();
        }

        void collectLeafGroups(List<Section> leaves) {
            if (isLeafGroup()) {
                leaves.add(this);
                return;
            }
            for (Section child : children) {
                child.collectLeafGroups(leaves);
            }
        }
    }

    /**
     * 识别整卷结构。
     */
    public PaperStructure detect(String paperText) {
        List<String> lines = List.of(paperText.split("\n", -1));

        List<Integer> majorStarts = new ArrayList<>();
        List<String> majorTitles = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = MAJOR_HEADING.matcher(lines.get(i));
            if (matcher.matches()) {
                majorStarts.add(i);
                majorTitles.add(lines.get(i).trim());
            }
        }

        if (majorStarts.isEmpty()) {
            // 没有任何大题标题：整卷作为一个大题
            String whole = paperText.trim();
            List<Integer> questionNos = findQuestionNos(whole);
            Section single = new Section("全卷", null, 1, extractMaterial(whole), whole, questionNos, List.of());
            return new PaperStructure(List.of(single));
        }

        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < majorStarts.size(); i++) {
            int start = majorStarts.get(i);
            int end = i + 1 < majorStarts.size() ? majorStarts.get(i + 1) : lines.size();
            sections.add(buildSection(lines, start, end, 1, majorTitles.get(i)));
        }
        return new PaperStructure(sections);
    }

    private Section buildSection(List<String> lines, int start, int end, int level, String title) {
        List<Integer> minorStarts = new ArrayList<>();
        List<String> minorTitles = new ArrayList<>();
        // 跳过标题行本身，从下一行开始找小节
        for (int i = start + 1; i < end; i++) {
            Matcher matcher = MINOR_HEADING.matcher(lines.get(i));
            if (matcher.matches()) {
                minorStarts.add(i);
                minorTitles.add(lines.get(i).trim());
            }
        }

        if (minorStarts.isEmpty()) {
            String slice = joinLines(lines, start, end);
            return new Section(title, null, level, extractMaterial(slice), slice, findQuestionNos(slice), List.of());
        }

        List<Section> children = new ArrayList<>();
        for (int i = 0; i < minorStarts.size(); i++) {
            int childStart = minorStarts.get(i);
            int childEnd = i + 1 < minorStarts.size() ? minorStarts.get(i + 1) : end;
            String slice = joinLines(lines, childStart, childEnd);
            children.add(new Section(minorTitles.get(i), null, level + 1, extractMaterial(slice), slice,
                findQuestionNos(slice), List.of()));
        }
        String wholeSlice = joinLines(lines, start, end);
        return new Section(title, null, level, null, wholeSlice, findQuestionNos(wholeSlice), children);
    }

    private String joinLines(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start, Math.min(end, lines.size()))).trim();
    }

    List<Integer> findQuestionNos(String text) {
        List<Integer> numbers = new ArrayList<>();
        for (String line : text.split("\n")) {
            Matcher matcher = QUESTION_NO.matcher(line);
            if (matcher.find()) {
                numbers.add(Integer.parseInt(matcher.group(1)));
            }
        }
        return numbers;
    }

    /**
     * 抽取材料原文：第一个题号之前的内容（去掉明显的说明性短句）。
     */
    String extractMaterial(String slice) {
        String[] lines = slice.split("\n", -1);
        StringBuilder material = new StringBuilder();
        for (String line : lines) {
            if (QUESTION_NO.matcher(line).find()) {
                break;
            }
            if (MAJOR_HEADING.matcher(line).matches() || MINOR_HEADING.matcher(line).matches()) {
                continue;
            }
            material.append(line).append('\n');
        }
        String text = material.toString().trim();
        if (text.length() < MIN_MATERIAL_CHARS) {
            return null;
        }
        return text;
    }

    /**
     * 材料是否带显式的「材料一 / 阅读下面的文字」标记。
     */
    boolean hasMaterialMarker(String text) {
        if (text == null) {
            return false;
        }
        for (String line : text.split("\n")) {
            if (MATERIAL_MARKER.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }
}
