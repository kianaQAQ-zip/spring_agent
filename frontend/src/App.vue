<script setup>
import { useRoute } from 'vue-router'
import { computed } from 'vue'

const route = useRoute()
const active = computed(() => route.path)
</script>

<template>
  <div class="layout">
    <header class="header">
      <span class="logo">🛒 电商客服 Agent</span>
      <nav class="nav">
        <router-link to="/chat" :class="{ active: active === '/chat' }">客户聊天窗</router-link>
        <router-link to="/confirm" :class="{ active: active === '/confirm' }">坐席确认台</router-link>
      </nav>
    </header>
    <main class="main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.layout { display: flex; flex-direction: column; height: 100vh; }
.header {
  display: flex; align-items: center; gap: 32px;
  padding: 0 24px; height: 56px;
  background: #1f2d3d; color: #fff;
}
.logo { font-size: 17px; font-weight: 600; }
.nav { display: flex; gap: 8px; }
.nav a {
  color: #c0c4cc; text-decoration: none; padding: 6px 14px;
  border-radius: 6px; font-size: 14px;
}
.nav a.active { background: #409eff; color: #fff; }
.main { flex: 1; overflow: hidden; }
</style>
