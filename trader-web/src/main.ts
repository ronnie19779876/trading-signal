import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
// 暗色变量必须在亮色之后引入：靠 <html class="dark"> 生效，不加这行整站没有暗色。
import 'element-plus/theme-chalk/dark/css-vars.css'
import App from './App.vue'
import router from './router'
import './styles/base.css'

// locale：不传的话日期面板是 "September / Sun Mon Tue"、空表格是 "No Data"，中文界面里夹英文。
createApp(App).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
