<script setup lang="ts">
import { inject, onMounted, ref } from "vue";
import { api, mutate } from "./api";
import MeetingPeoplePicker from "./MeetingPeoplePicker.vue";
import type { MeetingOption, MeetingPerson } from "./meeting";

defineProps<{ departments: MeetingOption[] }>();
const groups = [
  { code: "MEETING_ORGANIZER", label: "周会发起权限" },
  { code: "MEETING_URGENT", label: "紧急会议权限" },
] as const;
type Code = (typeof groups)[number]["code"];
type Grant = MeetingPerson & { code: Code };
const grants = ref<Grant[]>([]);
const error = ref("");
const notice = ref("");
const loading = ref(true);
const busy = ref(false);
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;

/** 每次保存后从服务端重新读取两组任职。 */
async function load() {
  grants.value = (
    await api<{ items: Grant[] }>("/admin/meetings/permissions")
  ).items;
}
onMounted(async () => {
  try {
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
});

async function change(
  person: MeetingPerson,
  group: (typeof groups)[number],
  granted: boolean,
) {
  if (busy.value) return;
  busy.value = true;
  notice.value = error.value = "";
  try {
    const action = granted ? "授予" : "撤销";
    const password = await askPassword(
      "确认" +
        action +
        " " +
        person.name +
        " 的" +
        group.label +
        "，该员工需重新登录。",
    );
    if (password === null) return;
    await mutate(
      "/admin/meetings/permissions/" + person.id,
      "PUT",
      { code: group.code, granted },
      person.id,
      "USER",
      (granted ? "GRANT_" : "REVOKE_") + group.code,
      password,
    );
    await load();
    notice.value = person.name + "的" + group.label + "已" + action;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="room-panel" aria-labelledby="meeting-permissions-title">
    <header class="room-panel-head">
      <h2 id="meeting-permissions-title">会议权限</h2>
    </header>
    <p v-if="error" class="form-error room-panel-footer" role="alert">
      {{ error }}
    </p>
    <p v-if="notice" class="room-panel-footer" role="status">{{ notice }}</p>
    <p v-if="loading" class="room-panel-footer" role="status">读取授权名单中</p>
    <div v-else class="permission-groups">
      <section
        v-for="group in groups"
        :key="group.code"
        class="permission-group"
        :aria-label="group.label"
      >
        <h3>{{ group.label }}</h3>
        <MeetingPeoplePicker
          v-if="!busy"
          :departments="departments"
          :label="'授权员工'"
          :excluded="
            grants
              .filter((grant) => grant.code === group.code)
              .map((grant) => grant.id)
          "
          @select="(person) => change(person, group, true)"
        />
        <ul
          v-if="grants.some((grant) => grant.code === group.code)"
          class="meeting-attendees"
        >
          <li
            v-for="grant in grants.filter((item) => item.code === group.code)"
            :key="grant.id"
          >
            <span>{{ grant.name }} · {{ grant.employeeNo }}</span>
            <button
              class="secondary"
              :disabled="busy"
              :aria-label="'撤销 ' + grant.name + ' 的' + group.label"
              @click="change(grant, group, false)"
            >
              撤销
            </button>
          </li>
        </ul>
        <p v-else class="permission-empty">暂无已授权员工</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.permission-groups {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr));
}
.permission-group {
  display: grid;
  align-content: start;
  gap: 12px;
  padding: 16px 20px;
  border: 1px solid var(--line);
  border-width: 1px 0 0;
}
.permission-group + .permission-group {
  border-inline-start-width: 1px;
}
.permission-group li {
  justify-content: space-between;
  flex-wrap: wrap;
}
.permission-empty {
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
