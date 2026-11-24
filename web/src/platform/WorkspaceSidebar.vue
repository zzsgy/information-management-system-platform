<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
import biolinkLogo from "./assets/biolink-logo.png";

defineProps<{
  applications: { code: string; name: string; icon: string }[];
  activeModule: string;
  activeRoute: string;
  groups: { name: string; items: { code: string; name: string }[] }[];
}>();
</script>

<template>
  <nav class="workspace-sidebar" aria-label="我的应用">
    <div class="workspace-sidebar-brand">
      <span class="workspace-sidebar-logo"
        ><img :src="biolinkLogo" alt="BIO-LINK"
      /></span>
    </div>
    <a
      href="#/home"
      class="workspace-module-link"
      :class="{ active: activeModule === 'home' }"
      :aria-current="activeModule === 'home' ? 'page' : undefined"
    >
      <Icon name="home" :size="20" />
      <span>首页</span>
    </a>
    <template v-for="item in applications" :key="item.code">
      <a
        :href="'#/' + item.code"
        class="workspace-module-link"
        :class="{ active: activeModule === item.code }"
        :aria-current="
          activeModule === item.code && !groups.length ? 'page' : undefined
        "
      >
        <span class="application-icon"
          ><Icon :name="item.icon" :size="22"
        /></span>
        <span>{{ item.name }}</span>
        <span class="application-trailing">
          <Icon
            :name="
              activeModule === item.code && groups.length ? 'down' : 'chevron'
            "
            :size="15"
          />
        </span>
      </a>
      <div
        v-if="activeModule === item.code && groups.length"
        class="workspace-subnav"
        :aria-label="item.name + '子模块'"
      >
        <section v-for="group in groups" :key="group.name">
          <h2 v-if="group.name">{{ group.name }}</h2>
          <a
            v-for="child in group.items"
            :key="child.code"
            :href="'#/' + child.code"
            :aria-current="child.code === activeRoute ? 'page' : undefined"
            >{{ child.name }}</a
          >
        </section>
      </div>
    </template>
  </nav>
</template>

<style scoped>
.workspace-sidebar {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 6px;
  padding: 16px 12px;
  border-radius: 14px;
  border-inline-end: 1px solid var(--line);
  background: linear-gradient(165deg, var(--brand-soft), var(--surface) 48%);
  box-shadow: var(--shadow);
  color: var(--ink);
}

.workspace-sidebar-brand {
  padding: 0 4px 14px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--line);
}

.workspace-sidebar-logo {
  display: block;
  width: 86%;
  margin-inline: auto;
  padding: 9px 12px;
}

.workspace-sidebar-logo img {
  display: block;
  width: 100%;
  height: auto;
}

.workspace-module-link {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) 14px;
  align-items: center;
  gap: 9px;
  min-height: 45px;
  padding: 7px 9px;
  border-radius: 8px;
  color: var(--ink);
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
}

.workspace-module-link:hover,
.workspace-module-link:focus-visible {
  background: var(--surface-muted);
  color: var(--brand-hover);
}

.workspace-module-link:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.workspace-module-link.active {
  color: var(--brand-hover);
  background: var(--brand-soft);
  box-shadow: inset 3px 0 var(--brand);
}

.workspace-sidebar .application-icon,
.workspace-sidebar .application-trailing {
  display: flex;
  align-items: center;
  justify-content: center;
  width: auto;
  height: auto;
  background: transparent;
  color: inherit;
}

.workspace-sidebar .application-trailing {
  opacity: 0.65;
}

.workspace-subnav {
  margin-inline: 19px 2px;
  padding-left: 10px;
  border-left: 1px solid var(--line);
}

.workspace-subnav h2 {
  color: var(--text-secondary);
  font-size: 11px;
  margin: 8px 6px 4px;
}

.workspace-subnav a {
  display: block;
  padding: 6px;
  border-radius: 5px;
  color: var(--ink);
  font-size: 12px;
}

.workspace-subnav a:hover,
.workspace-subnav a:focus-visible,
.workspace-subnav a[aria-current="page"] {
  background: var(--brand-soft);
  color: var(--brand-hover);
}

.workspace-subnav a:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

@media (max-width: 760px) {
  .workspace-sidebar {
    margin: 12px var(--page-gutter) 0;
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 140px), 1fr));
    padding: 10px;
  }

  .workspace-sidebar-brand {
    display: none;
  }

  .workspace-subnav {
    grid-column: 1 / -1;
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>
