# 组卷任务

- 学科：{subjectName}
- 难度：{difficultyDescription}
- 题量：共 {questionCount} 题
- 题型偏好：{typePreference}

## 覆盖的知识板块
| 板块 key | 板块名称 | 大致题量 |
|------|------|------|
{categoryTable}

## 最近已出过的题目（避免重复）
{historicalSection}

## 输出要求
1. `sections` 数组：每个知识板块一项。`title` 用板块中文名，`knowledgeCategory` 用板块 key。
2. 需要原文的板块（阅读理解、完形填空、文言文、现代文阅读）必须填写 material 字段，其中包含 title 与 content 两个子字段；其它板块留空。
3. 每题的 `questionType` 只能是：SINGLE_CHOICE / MULTI_CHOICE / FILL_BLANK / SHORT_ANSWER / READING / WRITING / OTHER。
4. 选择题必须给 `options`（key 用 A/B/C/D，text 不含标签）与 `referenceAnswer`（如 "B" 或 "BD"）。
5. 每题必须给 `fullScore`（正整数）；整卷总分由后端汇总，你不用给总分。
6. 长度上限：单篇材料不超过 {maxMaterialChars} 字，整卷材料合计不超过 {maxTotalMaterialChars} 字，单个题干不超过 {maxStemChars} 字。
7. 题量分配要贴近上表的「大致题量」，四舍五入后总数必须等于 {questionCount}。
