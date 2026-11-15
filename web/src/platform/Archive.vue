<script setup lang="ts">
import { can } from "./api";
import ArchiveLedger from "./ArchiveLedger.vue";
import ArchiveBrowse from "./ArchiveBrowse.vue";
</script>
<template>
  <ArchiveLedger v-if="can('ARCHIVE.CATALOG_MANAGE')" />
  <ArchiveBrowse v-else />
</template>
