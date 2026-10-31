import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// The dark variables must be imported after the light ones: they take effect via <html class="dark">; without this
// line the whole site has no dark mode.
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import './styles/base.css'

// locale: without it the date picker shows "September / Sun Mon Tue" and empty tables say "No Data" -- English
// mixed into a Chinese UI.
createApp(App).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
