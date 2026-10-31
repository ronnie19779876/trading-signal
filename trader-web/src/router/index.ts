import { createRouter, createWebHistory } from 'vue-router'

/**
 * Every frontend route is a single-segment path without a dot: the backend SpaForwardController only forwards
 * those, otherwise opening or refreshing a deep link directly is a 404 (SpaForwardControllerTest checks every route
 * in this file). The catch-all route at the end is a parameter route and is exempt from that constraint.
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    // Menu order (set by the user on 2026-09-19): dashboard, positions, account, fundamentals, entry signals, trades, system
    { path: '/', name: 'dashboard', component: () => import('../pages/DashboardPage.vue'), meta: { title: '仪表盘' } },
    { path: '/positions', name: 'positions', component: () => import('../pages/PositionsPage.vue'), meta: { title: '持仓' } },
    { path: '/account', name: 'account', component: () => import('../pages/AccountPage.vue'), meta: { title: '账户' } },
    { path: '/fundamentals', name: 'fundamentals', component: () => import('../pages/FundamentalsPage.vue'), meta: { title: '基本面' } },
    { path: '/signals', name: 'signals', component: () => import('../pages/SignalsPage.vue'), meta: { title: '入场信号' } },
    { path: '/selection', name: 'selection', component: () => import('../pages/SelectionPage.vue'), meta: { title: '量化选股' } },
    { path: '/trades', name: 'trades', component: () => import('../pages/PlaceholderPage.vue'), meta: { title: '交易' } },
    { path: '/system', name: 'system', component: () => import('../pages/SystemInfoPage.vue'), meta: { title: '系统' } },
    // The market-data base page is no longer in the menu; where it belongs was to be decided during the page-by-page
    // rework; for now it is reached from the system page.
    // In 3.0.6 the market-data base page was merged into the system page's "market data" tab; the old address and
    // bookmarks redirect there.
    { path: '/marketdata', redirect: { path: '/system', query: { tab: 'marketdata' } } },
    // Without this route, opening a path that does not exist gives a blank page plus a console warning (the backend
    // forwards single-segment paths to index.html).
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('../pages/NotFoundPage.vue'), meta: { title: '找不到页面' } },
  ],
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · T-Signal` : 'T-Signal'
})

export default router
