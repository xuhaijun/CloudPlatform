<script setup lang="ts">
/**
 * 根组件。
 *
 * 刻意保持「零逻辑」：所有布局都在 AppLayout 中，
 * 因为登录页不需要侧边栏 —— 若把布局写在这里，就得用 v-if 判断当前路由，
 * 那会让根组件承担路由感知职责，也让登录页与业务页的切换产生额外重渲染。
 */
</script>

<template>
  <router-view />
</template>
