/**
 * 管理端数据范围（多用户隔离的显式开关）。
 *
 * - 「只看我的」= 不传参数（后端按当前登录用户过滤）；
 * - 「查看全部」= `scope=all`，**只有管理员**才会被后端采纳（后端按角色二次校验），
 *   普通用户即使传了也只会拿到自己的数据。
 */
export type DataScopeParam = 'all' | undefined;

/** 管理员「查看全部」的查询串，非管理员视图返回空串 */
export function scopeQuery(scope?: DataScopeParam): string {
  return scope === 'all' ? 'scope=all' : '';
}

/** 把 scope=all 追加到已有查询参数上 */
export function applyScope(params: URLSearchParams, scope?: DataScopeParam): void {
  if (scope === 'all') {
    params.append('scope', 'all');
  }
}
