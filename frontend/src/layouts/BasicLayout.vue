<template>
  <div id="basicLayout">
    <a-layout style="min-height: 100vh">
      <a-layout-header class="header">
        <GlobalHeader />
      </a-layout-header>

      <a-layout>
        <GlobalSider class="sider"/>
        <a-layout-content class="content">
          <router-view />
        </a-layout-content>
      </a-layout>



      <a-layout-footer class="footer">
        <div>by 张又升</div>
      </a-layout-footer>
    </a-layout>
  </div>
</template>

<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalSider from '@/components/GlobalSider.vue'
</script>

<style scoped>
#basicLayout .header {
  padding-inline: 20px;
  color: unset;
  background: white;
  margin-bottom: 1px;
}

#basicLayout .content {
  padding: 28px;
  background: linear-gradient(to right, #fefefe, #fff);
  margin-bottom: 30px;
}

#basicLayout .sider {
  background: white;
  border-right: 0.5px solid #e5e5e5;
}

#basicLayout :deep(.ant-menu-root) {
  border-bottom: none !important;
  border-inline-end: none !important;
}

#basicLayout .footer {
  background: #efefef;
  padding: 16px;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  text-align: center;
}
</style>
