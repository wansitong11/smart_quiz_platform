# Task
请为资料《{knowledgeBaseName}》命制 {questionCount} 道测验题。

# 难度要求
{difficultyDescription}

请按**资料本身所属的学段**把握深浅：小学资料出小学难度的题，初中资料出初中的，高中资料出高中的；
判断不了学段时，以资料中出现的内容深度为准，不要默认按高考标准命题。

# Existing Categories
{existingCategories}

# Existing Questions
以下题目已经出过（本次生成是**追加**到现有题库，不会覆盖它们），请从不同知识点或不同考察角度出题，
避免与这些题目在题面或考察点上重复；如果实在没有新的角度，可以少出几道：
{existingQuestions}

# Knowledge Base Context
{context}

# Output Requirements
- category: 知识板块，遵循 system prompt 的归类规则（最多 {categoryLimit} 个不同方向，优先复用已有）
- question: 题干（与模拟测验的 stem 对齐）
- questionType: 只能是 SINGLE_CHOICE / MULTI_CHOICE / FILL_BLANK / SHORT_ANSWER / READING / WRITING / OTHER
- options: 选择题必填，数组，每项含 key 与 text 两个字段（key 用 A/B/C/D，text 不含标签），至少 2 项；非选择题返回空数组
- fullScore: 该题分值（正整数，按资料所属学段的常规赋分：小学多小题、分值宜小；选择题一般 2~5 分，简答 4~10 分，写作 10~60 分）
- referenceAnswer: 参考答案（选择题给选项 key，如 "B" 或 "BD"；填空给标准文本；简答给要点）
- analysis: 题目解析（讲清为什么，30~120 字）
- keyPoints: 评分要点列表（主观题用，客观题可空）
- topicSummary: 不超过 80 字的知识点摘要（用于避免重复出题）
- type: 可选的分类 key，不需要时留空
- followUps: 固定返回空数组（本平台测验不生成追问）
