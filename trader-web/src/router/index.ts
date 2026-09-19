import { createRouter, createWebHistory } from 'vue-router'

/**
 * 前端路由都是单段、不含点的路径：后端 SpaForwardController 只转发这种，
 * 否则直接打开或刷新深链接会 404（SpaForwardControllerTest 逐条核对本文件）。
 * 末尾的兜底路由是参数路由，不参与那条约束。
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 菜单顺序（2026-09-19 用户定）：仪表盘、持仓、账户、基本面、入场信号、交易、系统
    { path: '/', name: 'dashboard', component: () => import('../pages/DashboardPage.vue'), meta: { title: '仪表盘' } },
    { path: '/positions', name: 'positions', component: () => import('../pages/PlaceholderPage.vue'), meta: { title: '持仓' } },
    { path: '/account', name: 'account', component: () => import('../pages/AccountPage.vue'), meta: { title: '账户' } },
    { path: '/fundamentals', name: 'fundamentals', component: () => import('../pages/FundamentalsPage.vue'), meta: { title: '基本面' } },
    { path: '/signals', name: 'signals', component: () => import('../pages/SignalsPage.vue'), meta: { title: '入场信号' } },
    { path: '/trades', name: 'trades', component: () => import('../pages/PlaceholderPage.vue'), meta: { title: '交易' } },
    { path: '/system', name: 'system', component: () => import('../pages/SystemInfoPage.vue'), meta: { title: '系统' } },
    // 行情数据底座不在菜单里了，归属待逐页调整时再定；暂时从系统页进入
    { path: '/marketdata', name: 'marketdata', component: () => import('../pages/MarketDataPage.vue'), meta: { title: '行情数据' } },
    // 没有这条时，打开不存在的路径是白屏加一句控制台告警（后端会把单段路径转发给 index.html）。
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../pages/NotFoundPage.vue'), meta: { title: '找不到页面' } },
  ],
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · T-Signal` : 'T-Signal'
})

export default router
