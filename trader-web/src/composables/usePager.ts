import { computed, ref, watch, type Ref } from 'vue'

/**
 * 前端分页：列表整体在内存里，筛选与排序作用于全部，这里只切出当前页。
 *
 * 之前是「列表引用一变就回到第 1 页」。对自动刷新的表这会让分页**永远翻不过第 1 页**：
 * 跑批页的作业记录每 5 秒重取一次，`filtered` 每次都算出一个新数组，watch 就把页码打回 1
 * （2026-09-25 全项目审查发现）。
 *
 * 改成：页码只在越界时收回最后一页（重新加载后条数变少也不会停在空页）；
 * 「换了筛选条件回到第 1 页」由调用方把筛选条件传进 `resetOn` 显式表达——
 * 只有那才是真的该回到第 1 页，定时刷新不是。
 *
 * @param resetOn 这些值一变就回到第 1 页（通常是筛选条件）
 */
export function usePager<T>(list: Ref<T[]>, size = 20, resetOn: Ref<unknown>[] = []) {
  const page = ref(1)
  const pageSize = ref(size)
  const paged = computed(() => list.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
  const total = computed(() => list.value.length)
  const lastPage = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

  watch([total, pageSize], () => {
    if (page.value > lastPage.value) page.value = lastPage.value
  })
  if (resetOn.length) watch(resetOn, () => (page.value = 1))

  return { page, pageSize, paged, total }
}
