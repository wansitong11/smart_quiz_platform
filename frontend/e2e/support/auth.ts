import type { Page } from '@playwright/test';

const AUTH_USER = {
  id: 1,
  username: 'e2e',
  displayName: 'E2E 用户',
  email: null,
  roles: ['ROLE_ADMIN'],
  permissions: ['user:manage', 'provider:manage'],
};

/**
 * 前端启动时会调用 /api/auth/refresh 做静默续期，页面也会请求 /api/auth/me。
 * E2E 环境没有真实后端，必须把这两个接口 mock 成"已登录"，
 * 否则路由守卫会把用例全部挡到登录页。
 */
export async function mockAuthenticated(page: Page): Promise<void> {
  await page.route('**/api/auth/refresh', route => route.fulfill({
    json: {
      code: 200,
      message: 'success',
      data: {
        accessToken: 'e2e-access-token',
        tokenType: 'Bearer',
        expiresIn: 7200,
        user: AUTH_USER,
      },
    },
  }));
  await page.route('**/api/auth/me', route => route.fulfill({
    json: { code: 200, message: 'success', data: AUTH_USER },
  }));
  await page.route('**/api/auth/logout', route => route.fulfill({
    json: { code: 200, message: 'success', data: null },
  }));
}
