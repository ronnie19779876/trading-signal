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
    { path: '/', name: 'system', component: () => import('../pages/SystemInfoPage.vue'), meta: { title: '系统' } },
    { path: '/marketdata', name: 'marketdata', component: () => import('../pages/MarketDataPage.vue'), meta: { title: '行情' } },
    { path: '/fundamentals', name: 'fundamentals', component: () => import('../pages/FundamentalsPage.vue'), meta: { title: '基本面' } },
    { path: '/account', name: 'account', component: () => import('../pages/AccountPage.vue'), meta: { title: '账户' } },
    { path: '/signals', name: 'signals', component: () => import('../pages/SignalsPage.vue'), meta: { title: '信号' } },
    // 没有这条时，打开不存在的路径是白屏加一句控制台告警（后端会把单段路径转发给 index.html）。
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../pages/NotFoundPage.vue'), meta: { title: '找不到页面' } },
  ],
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · T-Signal` : 'T-Signal'
})

export default router
