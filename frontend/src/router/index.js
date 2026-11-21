import { createRouter, createWebHashHistory } from 'vue-router'
import AppLayout from '../components/AppLayout.vue'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
      { path: 'assets', name: 'assets', component: () => import('../views/AssetsView.vue') },
      { path: 'inspections', name: 'inspections', component: () => import('../views/InspectionsView.vue') },
      { path: 'clusters', name: 'clusters', component: () => import('../views/ClustersView.vue') },
      { path: 'datasets', name: 'datasets', component: () => import('../views/DatasetsView.vue') },
      { path: 'cohorts', name: 'cohorts', component: () => import('../views/CohortsView.vue') },
      { path: 'rules', name: 'rules', component: () => import('../views/RulesView.vue') },
      { path: 'audit', name: 'audit', component: () => import('../views/AuditView.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  const token = localStorage.getItem('auth_token')
  if (to.name !== 'login' && !token) return { name: 'login' }
  if (to.name === 'login' && token) return { name: 'dashboard' }
  return true
})

export default router
