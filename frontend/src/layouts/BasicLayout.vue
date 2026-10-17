<template>
  <div id="basicLayout" :class="{'workbench-layout':isWorkbench}">
    <a-layout style="min-height: 100vh">
      <a-layout-header class="header">
        <GlobalHeader />
      </a-layout-header>

      <a-layout>
        <GlobalSider v-if="!isWorkbench" class="sider"/>
        <a-layout-content class="content">
          <router-view :key="$route.path" />
        </a-layout-content>
      </a-layout>



      <a-layout-footer v-if="!isWorkbench" class="footer">
        <div>by 张又升</div>
      </a-layout-footer>
    </a-layout>
  </div>
</template>

<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalSider from '@/components/GlobalSider.vue'
import { computed } from 'vue'
import { useRoute } from 'vue-router'
const route=useRoute()
const isWorkbench=computed(()=>/^\/(?:agent\/picture|video\/(?:editor|hypit))\//.test(route.path))
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

<style scoped>
#basicLayout.workbench-layout { height:100dvh; overflow:hidden; }
.workbench-layout > :deep(.ant-layout) { height:100%; min-height:0!important; overflow:hidden; }
.workbench-layout :deep(.ant-layout-header) { flex:0 0 64px; }
.workbench-layout :deep(.ant-layout-has-sider),.workbench-layout :deep(.ant-layout-header + .ant-layout) { flex:1; min-height:0; }
#basicLayout.workbench-layout .content { padding:12px; margin:0; min-width:0; min-height:0; overflow:hidden; background:var(--gallery-bg); }
@media(max-width:900px){#basicLayout.workbench-layout .content{padding:8px}}
</style>
