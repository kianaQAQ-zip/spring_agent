<script setup>
import { useRoute } from 'vue-router'
import { computed } from 'vue'
import { useThemeStore } from './stores/theme'

const route = useRoute()
const active = computed(() => route.path)
const themeStore = useThemeStore()

const navs = [
  { path: '/chat', label: '客户聊天窗', icon: 'ChatDotRound' },
  { path: '/confirm', label: '坐席确认台', icon: 'Tickets' },
  { path: '/kb', label: '知识库上传', icon: 'FolderOpened' }
]
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">
        <div class="logo-icon">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15a2 2 0 01-2 2H7l-4 4V5a2 2 0 012-2h14a2 2 0 012 2z" />
          </svg>
        </div>
        <span class="logo-text">电商客服 Agent</span>
      </div>

      <nav class="nav">
        <router-link
          v-for="n in navs"
          :key="n.path"
          :to="n.path"
          class="nav-item"
          :class="{ active: active === n.path }"
        >
          <el-icon :size="17"><component :is="n.icon" /></el-icon>
          <span>{{ n.label }}</span>
        </router-link>
      </nav>

      <div class="side-bottom">
        <button class="icon-btn" :title="themeStore.theme === 'light' ? '切换暗色模式' : '切换亮色模式'" @click="themeStore.toggle()">
          <svg v-if="themeStore.theme === 'light'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12.79A9 9 0 1111.21 3 7 7 0 0021 12.79z" />
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="5" /><line x1="12" y1="1" x2="12" y2="3" /><line x1="12" y1="21" x2="12" y2="23" /><line x1="4.22" y1="4.22" x2="5.64" y2="5.64" /><line x1="18.36" y1="18.36" x2="19.78" y2="19.78" /><line x1="1" y1="12" x2="3" y2="12" /><line x1="21" y1="12" x2="23" y2="12" /><line x1="4.22" y1="19.78" x2="5.64" y2="18.36" /><line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
          </svg>
        </button>
        <span class="version">v1.0.0</span>
      </div>
    </aside>

    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.layout { display: flex; height: 100vh; }
.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  background: var(--bg);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transition: background 200ms ease, border-color 200ms ease;
}
.logo {
  display: flex; align-items: center; gap: 10px;
  padding: 18px 16px 14px;
}
.logo-icon {
  width: 34px; height: 34px; border-radius: 9px;
  background: var(--brand-gradient);
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
  box-shadow: var(--shadow-brand);
}
.logo-text {
  font-size: 15px; font-weight: 700; color: var(--text);
  white-space: nowrap;
}
.nav { flex: 1; display: flex; flex-direction: column; gap: 2px; padding: 6px 10px; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 12px; border-radius: var(--radius-sm);
  font-size: 14px; color: var(--text-secondary); text-decoration: none;
  transition: background 120ms ease, color 120ms ease;
}
.nav-item:hover { background: var(--bg-hover); color: var(--text); }
.nav-item.active { background: var(--brand-soft); color: var(--brand); font-weight: 600; }
.side-bottom {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px; border-top: 1px solid var(--border);
}
.icon-btn {
  width: 32px; height: 32px; border-radius: 6px; border: none;
  background: transparent; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  color: var(--text-tertiary);
  transition: background 150ms ease, color 150ms ease;
}
.icon-btn:hover { background: var(--bg-hover); color: var(--text); }
.version { font-size: 11px; color: var(--text-muted); }
.main { flex: 1; min-width: 0; overflow: hidden; background: var(--bg-card); transition: background 200ms ease; }
</style>
