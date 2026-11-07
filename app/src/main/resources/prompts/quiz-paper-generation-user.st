# 组卷任务

- 考试：{examName}
- 科目：{subjectName}
- 难度：{difficultyDescription}
- 题量：共 {questionCount} 题
- 题型配比：{typePreference}

## 覆盖的考点
| 考点 key | 考点名称 | 大致题量 |
|------|------|------|
{categoryTable}

## 最近已出过的题目（避免重复）
{historicalSection}

## 输出要求
1. `sections` 数组：每个考点一项。`title` 用考点中文名，`knowledgeCategory` 用考点 key。
2. 需要原文的考点必须填写 material 字段（title + content 两个子字段）；其它考点留空。
3. 每题的 `questionType` 只能是：SINGLE_CHOICE / MULTI_CHOICE / TRUE_FALSE / FILL_BLANK / SHORT_ANSWER / READING / WRITING / CASE_ANALYSIS / OTHER。
4. 选择题与判断题必须给 `options`（key 用 A/B/C/D，text 不含标签）与 `referenceAnswer`（单选如 "B"、多选如 "BD"、判断如 "A"）。
5. 判断题的 options 固定为两个选项：A 的 text 为「正确」，B 的 text 为「错误」，并给 referenceAnswer。
6. 每题必须给 `fullScore`（正整数）；整卷总分由后端汇总，你不用给总分。
7. 长度上限：普通板块单篇材料不超过 {maxMaterialChars} 字；阅读类板块单篇不超过 {maxReadingMaterialChars} 字；整卷材料合计不超过 {maxTotalMaterialChars} 字；单个题干不超过 {maxStemChars} 字。若原文超出上限，请先压缩到上限以内，再基于压缩后的原文命制题目，确保题目只考最终材料里有的内容。
8. 题量分配要贴近上表「大致题量」，并遵守题型配比，四舍五入后总数必须等于 {questionCount}。
