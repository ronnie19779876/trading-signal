import { computed, ref, watch, type Ref } from 'vue'

/**
 * 前端分页：列表整体在内存里，筛选与排序作用于全部，这里只切出当前页。
 * 列表一变（换了筛选条件、重新加载）回到第 1 页。
 */
export function usePager<T>(list: Ref<T[]>, size = 20) {
  const page = ref(1)
  const pageSize = ref(size)
  const paged = computed(() => list.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
  const total = computed(() => list.value.length)
  watch([list, pageSize], () => (page.value = 1))
  return { page, pageSize, paged, total }
}
