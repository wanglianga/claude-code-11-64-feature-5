<template>
  <router-view v-if="auth.loggedIn || $route.name === 'login'" />
</template>

<script setup>
import { useAuthStore } from './stores/auth'
const auth = useAuthStore()
</script>
