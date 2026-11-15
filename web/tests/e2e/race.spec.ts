import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

/** 延迟真实接口响应模拟弱网络，确认旧档案内容不会出现在新档案抽屉内。 */
test('快速切换档案后丢弃迟到的旧详情', async ({page, request}) => {
  const password = process.env.IMS_E2E_PASSWORD; // 隔离测试凭据仅从进程环境读取。
  if (!password) throw new Error('缺少隔离测试密码');
  const login = await request.post('/api/auth/login', {data: {username:'admin', password}});
  const actor = (await login.json()).data; // 用于准备本部门两份测试草稿。
  const headers = {Authorization: `Bearer ${actor.access_token}`}; // 不打印或保存认证头。
  const categories = (await (await request.get('/api/categories',{headers})).json()).data;
  const suffix = randomUUID().slice(0,8); // 避免与其他验收数据重名。
  const names = [`旧目标-${suffix}`, `新目标-${suffix}`];
  const ids:number[] = []; // 真实服务端生成的主键。
  let release: (()=>void) | undefined; // 显式释放慢响应，不依赖固定等待时间。
  try {
    for (const name of names) {
      const response = await request.post('/api/archives',{headers,data:{archive_name:name,dept_id:actor.dept_id,category_id:categories[0].id}});
      expect(response.ok()).toBeTruthy(); ids.push((await response.json()).data.id);
    }
    await page.goto('/'); await page.getByLabel('账号',{exact:true}).fill('admin'); await page.getByLabel('密码',{exact:true}).fill(password);
    await page.getByRole('button',{name:'登录',exact:true}).click(); await page.getByRole('link',{name:'档案台账',exact:true}).click();
    await expect(page.locator('.el-loading-mask:visible')).toHaveCount(0);
    await page.route(`**/api/archives/${ids[0]}`, async route => {
      const response = await route.fetch(); // 保留真实鉴权与数据，仅控制响应抵达顺序。
      await new Promise<void>(resolve=>{release=resolve;});
      await route.fulfill({response});
    });
    await page.getByRole('row').filter({hasText:names[0]}).getByRole('button',{name:'查看详情'}).click();
    await expect.poll(()=>!!release).toBe(true);
    await page.getByRole('dialog',{name:'档案详情'}).getByRole('button',{name:'关闭此对话框'}).click();
    await page.getByRole('row').filter({hasText:names[1]}).getByRole('button',{name:'查看详情'}).click();
    release!();
    const drawer = page.getByRole('dialog',{name:'档案详情'}); // 当前可见对象必须始终是第二份档案。
    await expect(drawer.getByRole('heading',{name:names[1],exact:true})).toBeVisible();
    await expect(drawer.getByRole('heading',{name:names[0],exact:true})).toHaveCount(0);
  } finally {
    release?.();
    await request.post('/api/auth/logout',{headers});
  }
});
