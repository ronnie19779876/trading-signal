import { computed, ref, watch, type Ref } from 'vue'

/**
 * Client-side pagination: the whole list is in memory, filtering and sorting apply to all of it, and this only
 * slices out the current page.
 *
 * It used to "go back to page 1 whenever the list reference changes". For auto-refreshing tables that meant the
 * pager **could never get past page 1**: the job history on the batch page is re-fetched every 5 seconds,
 * `filtered` computes a new array each time, and the watch snapped the page back to 1 (found in the 2026-09-25
 * full-project review).
 *
 * Now: the page is only pulled back to the last page when it goes out of range (so a reload with fewer rows never
 * leaves you on an empty page); "back to page 1 when the filter changes" is expressed explicitly by the caller
 * passing the filter refs in `resetOn` -- only that should really go back to page 1, a periodic refresh should not.
 *
 * @param resetOn going back to page 1 whenever any of these values changes (usually the filter criteria)
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
