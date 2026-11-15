import { createRouter, createWebHashHistory } from "vue-router";

/** 哈希路由在 Java 和普通静态服务器上均支持刷新，无需吞掉 API 的回退路由。 */
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: "/",
      component: () => import("./pages/Dashboard.vue"),
      meta: { title: "工作概览", permission: "archive.read" },
    },
    {
      path: "/archives",
      component: () => import("./pages/Archives.vue"),
      meta: { title: "档案台账", permission: "archive.read" },
    },
    {
      path: "/borrow",
      component: () => import("./pages/Business.vue"),
      props: { kind: "borrow" },
      meta: { title: "借阅管理", permission: "borrow.apply|borrow.approve" },
    },
    {
      path: "/destroy",
      component: () => import("./pages/Business.vue"),
      props: { kind: "destroy" },
      meta: {
        title: "销毁管理",
        permission: "destroy.apply|destroy.approve|destroy.execute",
      },
    },
    {
      path: "/damage",
      component: () => import("./pages/Business.vue"),
      props: { kind: "damage" },
      meta: { title: "异常登记", permission: "archive.read" },
    },
    {
      path: "/approval",
      component: () => import("./pages/Approvals.vue"),
      meta: { title: "审批中心", permission: "approval.todo.view" },
    },
    {
      path: "/templates",
      component: () => import("./pages/Templates.vue"),
      meta: { title: "审批规则", permission: "approval.template.manage" },
    },
    {
      path: "/notices",
      component: () => import("./pages/Notices.vue"),
      meta: { title: "公告通知", permission: "notice.read" },
    },
    {
      path: "/organization",
      component: () => import("./pages/Organization.vue"),
      meta: { title: "组织与账号", permission: "user.read" },
    },
    {
      path: "/catalog",
      component: () => import("./pages/Catalog.vue"),
      meta: { title: "基础设置", permission: "archive.read" },
    },
    {
      path: "/logs",
      component: () => import("./pages/Logs.vue"),
      meta: { title: "操作日志", permission: "log.read" },
    },
    {
      path: "/operations",
      component: () => import("./pages/Operations.vue"),
      meta: { title: "备份与校验", permission: "security.manage" },
    },
    { path: "/:pathMatch(.*)*", redirect: "/" },
  ],
});
