import type { IconType } from 'react-icons';
import {
  TbBook2,
  TbLanguage,
  TbMathFunction,
  TbSparkles,
} from 'react-icons/tb';

/**
 * Skill ID → react-icons 图标映射
 * 学科使用 Tabler Icons（Tb*）通用图标
 * 未命中的 skill 使用后端返回的 emoji 作为兜底
 */
const SKILL_ICON_MAP: Record<string, IconType> = {
  'chinese': TbBook2,
  'math': TbMathFunction,
  'english': TbLanguage,
  'custom': TbSparkles,
};

/**
 * 根据 skillId 获取对应的 react-icons 图标组件
 * 返回 null 表示未命中，调用方应使用后端返回的 emoji 兜底
 */
export function getSkillIcon(skillId: string): IconType | null {
  return SKILL_ICON_MAP[skillId] ?? null;
}
