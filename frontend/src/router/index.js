import { createRouter, createWebHistory } from 'vue-router'
import ChatView from '../views/ChatView.vue'
import ConfirmView from '../views/ConfirmView.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', name: 'chat', component: ChatView },
  { path: '/confirm', name: 'confirm', component: ConfirmView }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
