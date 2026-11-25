<script setup lang="ts">
import { computed, inject, nextTick, ref } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { api, setProfile, type Profile } from "./api";

/** 个人资料接口只附加本人名片资料，密码走现有独立接口。 */
interface AccountDetails extends Profile {
  organizationName: string | null;
  departmentName: string;
  jobTitle: string | null;
  supervisorName: string | null;
  mobilePhone: string | null;
}

type View = "details" | "mobile" | "password";
const inform = inject<(message: string) => void>("inform")!;
const dialog = ref<HTMLDialogElement | null>(null);
const details = ref<AccountDetails | null>(null);
const view = ref<View>("details");
const loading = ref(false);
const saving = ref(false);
const error = ref("");
const status = ref("");
const mobile = ref("");
const currentPassword = ref("");
const newPassword = ref("");
const confirmPassword = ref("");
let loadVersion = 0;

const fields = computed(() => {
  const data = details.value;
  if (!data) return [];
  return [
    { label: "工号", value: data.user.employeeNo },
    { label: "部门", value: data.departmentName },
    { label: "职位", value: data.jobTitle },
    { label: "直属主管", value: data.supervisorName },
    { label: "手机号", value: data.mobilePhone },
    { label: "公司邮箱", value: data.user.companyEmail },
    {
      label: "当前角色",
      value: data.roles.map((role) => role.name).join("、"),
    },
  ];
});

/** 切换操作或关闭时清空表单，密码仅在当前办理过程中保留。 */
function select(next: View) {
  view.value = next;
  error.value = status.value = "";
  mobile.value = next === "mobile" ? (details.value?.mobilePhone ?? "") : "";
  currentPassword.value = newPassword.value = confirmPassword.value = "";
  if (next !== "details")
    void nextTick(() =>
      dialog.value?.querySelector<HTMLInputElement>("form input")?.focus(),
    );
}

async function load() {
  const version = ++loadVersion;
  loading.value = true;
  error.value = "";
  try {
    const data = await api<AccountDetails>("/auth/profile");
    if (version === loadVersion) details.value = data;
  } catch (failure) {
    if (version === loadVersion) error.value = (failure as Error).message;
  } finally {
    if (version === loadVersion) loading.value = false;
  }
}

function open() {
  if (dialog.value?.open) return;
  details.value = null;
  select("details");
  dialog.value?.showModal();
  void load();
}

function close() {
  dialog.value?.close();
}

/** 关闭后清除本人资料，并让仍在途的读取结果失效。 */
function clear() {
  loadVersion++;
  details.value = null;
  select("details");
}

function onCancel(event: Event) {
  if (saving.value) event.preventDefault();
}

defineExpose({ open, close });

async function saveMobile() {
  saving.value = true;
  error.value = "";
  try {
    const result = await api<{ mobilePhone: string | null }>("/auth/profile", {
      method: "PATCH",
      body: JSON.stringify({ mobilePhone: mobile.value.trim() || null }),
    });
    if (details.value) details.value.mobilePhone = result.mobilePhone;
    select("details");
    status.value = result.mobilePhone ? "手机号已更新" : "手机号已清除";
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    saving.value = false;
  }
}

async function changePassword() {
  if (newPassword.value !== confirmPassword.value) {
    error.value = "两次输入的新密码不一致";
    return;
  }
  saving.value = true;
  error.value = "";
  try {
    await api("/auth/password", {
      method: "PUT",
      body: JSON.stringify({
        currentPassword: currentPassword.value,
        newPassword: newPassword.value,
      }),
    });
    close();
    setProfile(null);
    inform("密码已修改，请重新登录");
  } catch (failure) {
    error.value = (failure as Error).message;
    currentPassword.value = "";
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="form-dialog flow-dialog account-profile"
    aria-labelledby="account-profile-title"
    @cancel="onCancel"
    @close="clear"
  >
    <div class="flow-dialog-shell">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon" aria-hidden="true"
          ><Icon name="user" :size="22"
        /></span>
        <h2 id="account-profile-title">个人信息</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭个人信息"
          :disabled="saving"
          @click="close"
        >
          ×
        </button>
      </header>

      <div class="flow-dialog-body">
        <p v-if="loading" class="muted" role="status">正在读取个人信息…</p>

        <template v-else-if="details">
          <section class="account-identity" aria-label="员工身份">
            <span class="account-avatar" aria-hidden="true">{{
              details.user.name.slice(-1)
            }}</span>
            <div>
              <h3>{{ details.user.name }}</h3>
              <p v-if="details.organizationName">
                {{ details.organizationName }}
              </p>
            </div>
          </section>

          <dl v-if="view === 'details'" class="account-fields">
            <div v-for="field in fields" :key="field.label">
              <dt>{{ field.label }}</dt>
              <dd>{{ field.value || "—" }}</dd>
            </div>
          </dl>

          <form
            v-else-if="view === 'mobile'"
            id="account-mobile-form"
            class="flow-dialog-section account-edit"
            @submit.prevent="saveMobile"
          >
            <h3>修改手机号</h3>
            <label for="account-mobile">手机号</label>
            <input
              id="account-mobile"
              v-model="mobile"
              type="tel"
              autocomplete="tel"
              maxlength="32"
              aria-describedby="account-mobile-help"
              :disabled="saving"
            />
            <p id="account-mobile-help" class="muted">
              可填写带国际区号的号码，留空保存可清除。
            </p>
          </form>

          <form
            v-else
            id="account-password-form"
            class="flow-dialog-section account-edit"
            @submit.prevent="changePassword"
          >
            <h3>重置密码</h3>
            <label for="account-current-password">当前密码</label>
            <input
              id="account-current-password"
              v-model="currentPassword"
              type="password"
              autocomplete="current-password"
              maxlength="4096"
              required
              :disabled="saving"
            />
            <label for="account-new-password">新密码</label>
            <input
              id="account-new-password"
              v-model="newPassword"
              type="password"
              autocomplete="new-password"
              minlength="8"
              maxlength="4096"
              pattern="(?=.*[A-Za-z])(?=.*[0-9]).{8,}"
              aria-describedby="account-password-help"
              required
              :disabled="saving"
            />
            <label for="account-confirm-password">确认新密码</label>
            <input
              id="account-confirm-password"
              v-model="confirmPassword"
              type="password"
              autocomplete="new-password"
              maxlength="4096"
              required
              :disabled="saving"
            />
            <p id="account-password-help" class="muted">
              新密码至少 8 位，同时包含英文字母和数字。保存成功后须重新登录。
            </p>
          </form>
        </template>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <p v-if="status" class="account-status" role="status">{{ status }}</p>
      </div>

      <footer class="flow-dialog-footer">
        <template v-if="view === 'details'">
          <button type="button" class="secondary account-close" @click="close">
            关闭
          </button>
          <template v-if="details">
            <button type="button" class="secondary" @click="select('password')">
              重置密码
            </button>
            <button type="button" class="primary" @click="select('mobile')">
              修改手机号
            </button>
          </template>
          <button v-else-if="error" type="button" class="primary" @click="load">
            重新读取
          </button>
        </template>
        <template v-else>
          <button
            type="button"
            class="secondary"
            :disabled="saving"
            @click="select('details')"
          >
            取消
          </button>
          <button
            v-if="view === 'mobile'"
            type="submit"
            form="account-mobile-form"
            class="primary"
            :disabled="saving"
          >
            保存手机号
          </button>
          <button
            v-else
            type="submit"
            form="account-password-form"
            class="primary"
            :disabled="saving"
          >
            保存新密码
          </button>
        </template>
      </footer>
    </div>
  </dialog>
</template>

<style scoped>
.account-profile {
  --flow-dialog-width: 620px;
}
.account-identity {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 14px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px;
}
.account-avatar {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  color: var(--brand);
  background: var(--brand-soft);
  font-size: 20px;
  font-weight: 700;
}
.account-identity h3 {
  margin: 0;
  font-size: 20px;
}
.account-identity p {
  margin: 3px 0 0;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
}
.account-fields {
  margin: 0;
}
.account-fields > div {
  display: grid;
  grid-template-columns: minmax(78px, 1fr) minmax(0, 4fr);
  gap: 12px;
  padding: 9px 4px;
  border-bottom: 1px solid var(--line);
}
.account-fields dt {
  color: var(--text-secondary);
}
.account-fields dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.account-edit {
  gap: 8px;
}
.account-edit input {
  margin-bottom: 4px;
}
.account-edit p {
  font-size: 13px;
}
.account-close {
  margin-inline-end: auto;
}
.account-status {
  margin: 0;
  color: var(--brand);
}
</style>
