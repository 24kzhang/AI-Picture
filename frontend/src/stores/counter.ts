import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export const useCounterStore = defineStore('counter', () => {
  // 定义状态的初始值
  const count = ref(0)
  // 定义计算逻辑
  const doubleCount = computed(() => count.value * 2)
  // 定义怎么更改状态
  function increment() {
    count.value++
  }
  // 返回
  return { count, doubleCount, increment }
})
