import { createRouter, createWebHistory } from 'vue-router'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'system', component: () => import('../pages/SystemInfoPage.vue') },
  ],
})
