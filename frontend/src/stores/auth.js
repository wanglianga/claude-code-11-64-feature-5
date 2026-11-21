import { defineStore } from 'pinia'
import http from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('auth_token') || '',
    user: JSON.parse(localStorage.getItem('auth_user') || 'null')
  }),
  getters: {
    loggedIn: (s) => !!s.token,
    displayName: (s) => s.user?.displayName || s.user?.username || ''
  },
  actions: {
    async login(username, password) {
      const { data } = await http.post('/auth/login', { username, password })
      this.token = data.token
      this.user = { userId: data.userId, username: data.username, displayName: data.displayName, role: data.role }
      localStorage.setItem('auth_token', data.token)
      localStorage.setItem('auth_user', JSON.stringify(this.user))
    },
    async logout() {
      try {
        await http.post('/auth/logout')
      } catch (e) {
        /* 忽略退出时的网络错误 */
      }
      this.token = ''
      this.user = null
      localStorage.removeItem('auth_token')
      localStorage.removeItem('auth_user')
    }
  }
})
