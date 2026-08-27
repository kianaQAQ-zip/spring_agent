import { defineStore } from 'pinia'

// 会话 ID 客户/坐席共享（localStorage 持久化），确认台据此轮询 pending
export const useChatStore = defineStore('chat', {
  state: () => ({
    conversationId: localStorage.getItem('ecom.conversationId') || ('demo-' + Date.now())
  }),
  actions: {
    setConversationId(id) {
      this.conversationId = id
      localStorage.setItem('ecom.conversationId', id)
    }
  }
})
