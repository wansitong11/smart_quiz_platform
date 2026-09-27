import type { PaperNode, PaperTree } from '../types/interview';

/**
 * 深度优先收集题目节点（按试卷顺序）。
 */
export function collectQuestionNodes(tree?: PaperTree | null): PaperNode[] {
  if (!tree?.root) return [];
  const result: PaperNode[] = [];
  const walk = (node: PaperNode) => {
    if (node.type === 'QUESTION') {
      result.push(node);
    }
    (node.children ?? []).forEach(walk);
  };
  (tree.root.children ?? []).forEach(walk);
  return result;
}

export interface QuestionGroup {
  id: string;
  title: string;
  questionIndexes: number[];
}

/**
 * 按最近的小节给题目分段，用于答题卡与题组进度。
 * 遍历顺序即试卷顺序，因此「最近遇到的 SECTION」就是题目的所属小节。
 */
export function buildQuestionGroups(tree?: PaperTree | null): QuestionGroup[] {
  if (!tree?.root) return [];
  const groups: QuestionGroup[] = [];
  let current: QuestionGroup | null = null;
  const walk = (node: PaperNode) => {
    if (node.type === 'SECTION') {
      current = { id: node.id, title: node.title || '未分节', questionIndexes: [] };
      groups.push(current);
    }
    if (node.type === 'QUESTION' && node.questionIndex != null && current) {
      current.questionIndexes.push(node.questionIndex);
    }
    (node.children ?? []).forEach(walk);
  };
  (tree.root.children ?? []).forEach(walk);
  return groups.filter(group => group.questionIndexes.length > 0);
}

/**
 * 题目到根路径上的小节标题（用于报告页展示「所属小节」）。
 */
export function sectionTitlesOf(tree: PaperTree | null | undefined, questionIndex: number): string[] {
  if (!tree?.root) return [];
  const path: string[] = [];
  const walk = (node: PaperNode, titles: string[]): boolean => {
    const nextTitles = node.type === 'SECTION' && node.title ? [...titles, node.title] : titles;
    if (node.type === 'QUESTION' && node.questionIndex === questionIndex) {
      path.push(...nextTitles);
      return true;
    }
    for (const child of node.children ?? []) {
      if (walk(child, nextTitles)) return true;
    }
    return false;
  };
  (tree.root.children ?? []).some(child => walk(child, []));
  return path;
}

/**
 * 题组进度：已答 / 总题数。
 */
export function groupProgress(
  group: QuestionGroup,
  isAnswered: (questionIndex: number) => boolean
): { answered: number; total: number } {
  const answered = group.questionIndexes.filter(isAnswered).length;
  return { answered, total: group.questionIndexes.length };
}
