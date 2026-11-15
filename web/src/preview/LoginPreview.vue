<script setup lang="ts">
import { ref } from "vue";
import Icon from "./PlatformIcon.vue";

/** 预览登录只切换界面，不验证凭据、不发送网络请求。 */
const emit = defineEmits<{ enter: []; recover: [] }>();
/** 账号和密码仅暂存在当前组件内，离开登录页立即清空。 */
const account = ref("");
const password = ref("");
/** 密码显示开关方便检查输入，不保存或传输密码。 */
const showPassword = ref(false);
/** 直接进入首页设计稿；不创建正式用户会话。 */
function enterPreview() {
  account.value = "";
  password.value = "";
  emit("enter");
}
</script>

<template>
  <main class="p-login-minimal">
    <section class="p-login-panel" aria-labelledby="platform-login-title">
      <div class="p-minimal-brand">
        <span class="p-brand-mark" aria-hidden="true"><i /><i /><i /><i /></span>
        <div><h1 id="platform-login-title">信息管理系统</h1><span>INFORMATION MANAGEMENT SYSTEM</span></div>
      </div>
      <form class="p-minimal-form" @submit.prevent="enterPreview">
        <label for="preview-account">工号</label>
        <div class="p-form-input"><Icon name="user" :size="19" /><input id="preview-account" v-model="account" placeholder="请输入公司工号" autocomplete="off" maxlength="80" /></div>
        <label for="preview-password">密码</label>
        <div class="p-form-input"><Icon name="lock" :size="19" /><input id="preview-password" v-model="password" :type="showPassword ? 'text' : 'password'" placeholder="请输入密码" autocomplete="off" maxlength="128" /><button type="button" class="p-icon-button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword"><Icon :name="showPassword ? 'hide' : 'eye'" :size="18" /></button></div>
        <button type="button" class="p-login-recover" @click="emit('recover')">忘记密码？</button>
        <button type="submit" class="p-login-submit">登录<Icon name="arrow" :size="20" /></button>
      </form>
    </section>
  </main>
</template>
