import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import ConfirmView from '../views/ConfirmView.vue'
import KbView from '../views/KbView.vue'
import DashboardView from '../views/DashboardView.vue'
import HistoryView from '../views/HistoryView.vue'
import EvalView from '../views/EvalView.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: ChatView },
  { path: '/confirm', name: 'confirm', component: ConfirmView },
  { path: '/kb', name: 'kb', component: KbView },
  { path: '/dashboard', name: 'dashboard', component: DashboardView },
  { path: '/history', name: 'history', component: HistoryView },
  { path: '/eval', name: 'eval', component: EvalView }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
