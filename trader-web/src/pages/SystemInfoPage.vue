<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import OverviewTab from '../components/system/OverviewTab.vue'
import JobsTab from '../components/system/JobsTab.vue'
import MarketDataTab from '../components/system/MarketDataTab.vue'
import EventsTab from '../components/system/EventsTab.vue'
import { useAppStore } from '../stores/app'

/**
 * 系统页（运维总控台）：概况、跑批、行情数据（原行情数据底座页，/marketdata 跳到这里）、连接事件。
 * 当前标签放在 ?tab=，刷新与分享都能回到同一个标签。
 */
const route = useRoute()
const router = useRouter()
const app = useAppStore()

const TABS = ['overview', 'jobs', 'marketdata', 'events'] as const
type Tab = (typeof TABS)[number]
const tab = computed<Tab>({
  get: () => (TABS.includes(route.query.tab as Tab) ? (route.query.tab as Tab) : 'overview'),
  set: (t) => void router.replace({ query: { ...route.query, tab: t === 'overview' ? undefined : t } }),
})
</script>

<template>
  <div class="page">
    <PageHeader title="系统" hint="运行状态、网关、巡检、跑批与行情数据">
      <template #actions>
        <el-button size="small" @click="app.refresh()">刷新</el-button>
      </template>
    </PageHeader>
    <el-alert v-if="app.error" type="error" :title="'后端不可达：' + app.error" show-icon :closable="false" />

    <el-tabs v-model="tab">
      <el-tab-pane label="概况" name="overview" lazy><OverviewTab /></el-tab-pane>
      <el-tab-pane label="跑批" name="jobs" lazy><JobsTab /></el-tab-pane>
      <el-tab-pane label="行情数据" name="marketdata" lazy><MarketDataTab :active="tab === 'marketdata'" /></el-tab-pane>
      <el-tab-pane label="连接事件" name="events" lazy><EventsTab :active="tab === 'events'" /></el-tab-pane>
    </el-tabs>
  </div>
</template>
