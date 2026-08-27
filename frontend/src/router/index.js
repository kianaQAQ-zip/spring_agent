import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import ConfirmView from '../views/ConfirmView.vue'
import KbView from '../views/KbView.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: ChatView },
  { path: '/confirm', name: 'confirm', component: ConfirmView },
  { path: '/kb', name: 'kb', component: KbView }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
