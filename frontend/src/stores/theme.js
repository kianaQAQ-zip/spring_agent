import { defineStore } from 'pinia'

// 亮/暗主题（持久化），切换时同步 data-theme 与 ElementPlus dark 类
export const useThemeStore = defineStore('theme', {
  state: () => ({
    theme: localStorage.getItem('ecom.theme') || 'light'
  }),
  actions: {
    toggle() {
      this.theme = this.theme === 'light' ? 'dark' : 'light'
      this.apply()
      localStorage.setItem('ecom.theme', this.theme)
    },
    apply() {
      const isDark = this.theme === 'dark'
      document.documentElement.dataset.theme = isDark ? 'dark' : 'light'
      document.documentElement.classList.toggle('dark', isDark)
    }
  }
})
