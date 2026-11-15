<script setup lang="ts">
import { onUnmounted, ref } from "vue";
import { api } from "./api";
const emit = defineEmits<{ back: []; completed: [] }>();
/** 找回流程仅保存在当前页面内存，关闭时清除验证码和新密码。 */
const employeeNo = ref(""),
  flowId = ref(""),
  code = ref(""),
  password = ref(""),
  confirm = ref("");
const busy = ref(false),
  error = ref(""),
  notice = ref(""),
  remaining = ref(0);
const timer = window.setInterval(() => {
  if (remaining.value > 0) remaining.value--;
}, 1000);
onUnmounted(() => {
  clearInterval(timer);
  code.value = password.value = confirm.value = "";
});
/** 工号锁定到当前流程；换账号需返回登录重开，避免把旧验证码用于其他人。 */
async function send() {
  busy.value = true;
  error.value = "";
  try {
    const result = await api<{ flowId: string; retryAfterSeconds: number }>(
      "/auth/password-recovery",
      {
        method: "POST",
        body: JSON.stringify({
          employeeNo: employeeNo.value,
          ...(flowId.value ? { flowId: flowId.value } : {}),
        }),
      },
    );
    flowId.value = result.flowId;
    remaining.value = result.retryAfterSeconds;
    code.value = "";
    notice.value =
      "若账号可用且符合发送条件，验证码将发送至绑定的公司邮箱，请留意收件箱。";
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 成功后回到登录页，不在匿名浏览器自动建立员工会话。 */
async function complete() {
  if (password.value !== confirm.value) {
    error.value = "两次输入的新密码不一致";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await api("/auth/password-recovery/complete", {
      method: "POST",
      body: JSON.stringify({
        flowId: flowId.value,
        code: code.value,
        newPassword: password.value,
      }),
    });
    emit("completed");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
    password.value = confirm.value = "";
  }
}
</script>
<template>
  <form @submit.prevent="flowId ? complete() : send()">
    <label
      >公司工号<input
        v-model="employeeNo"
        autocomplete="username"
        maxlength="64"
        :readonly="!!flowId"
        required
    /></label>
    <p v-if="notice" class="recovery-notice" role="status">{{ notice }}</p>
    <template v-if="flowId">
      <label
        >邮箱验证码<input
          v-model="code"
          inputmode="numeric"
          autocomplete="one-time-code"
          pattern="[0-9]{6}"
          maxlength="6"
          placeholder="输入六位验证码"
          required
      /></label>
      <button
        class="text-button"
        type="button"
        :disabled="busy || remaining > 0"
        @click="send"
      >
        {{ remaining > 0 ? `${remaining} 秒后可重发` : "重新发送验证码" }}
      </button>
      <label
        >新密码<input
          v-model="password"
          type="password"
          autocomplete="new-password"
          minlength="8"
          pattern="(?=.*[A-Za-z])(?=.*[0-9]).{8,}"
          title="至少8位，同时包含英文字母和数字"
          maxlength="4096"
          required
      /></label>
      <label
        >再次输入新密码<input
          v-model="confirm"
          type="password"
          autocomplete="new-password"
          minlength="8"
          pattern="(?=.*[A-Za-z])(?=.*[0-9]).{8,}"
          title="至少8位，同时包含英文字母和数字"
          maxlength="4096"
          required
      /></label>
      <p class="muted">
        新密码至少8位，须包含英文字母和数字且不同于工号。验证码 10
        分钟有效；累计输错 5 次结束本次流程，重发不会清零。
      </p>
    </template>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <button class="primary full" :disabled="busy">
      {{ busy ? "正在处理…" : flowId ? "设置密码并返回登录" : "发送验证码" }}
    </button>
    <button
      class="text-button"
      type="button"
      :disabled="busy"
      @click="emit('back')"
    >
      返回登录
    </button>
  </form>
</template>
