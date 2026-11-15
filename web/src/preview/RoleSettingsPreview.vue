<script setup lang="ts">
import { computed, ref } from "vue";
import Icon from "./PlatformIcon.vue";

/** 示例角色的编辑结构，仅用于界面演示，不映射真实授权记录。 */
interface PreviewRole {
  /** 当前弹窗内的稳定角色编号。 */
  id: string;
  /** 管理员看到的角色名称。 */
  name: string;
  /** 角色用途说明。 */
  description: string;
  /** 用于勾选界面的示例权限编号集合。 */
  permissions: string[];
}
/** 示例用户可关联多个角色；用户和部门信息均为虚构数据。 */
interface PreviewMember {
  /** 示例用户编号，不是正式账号标识。 */
  id: string;
  /** 展示姓名。 */
  name: string;
  /** 展示所属部门。 */
  department: string;
  /** 当前弹窗中勾选的角色编号。 */
  roleIds: string[];
}

/** 原生弹窗提供焦点约束与 Esc 关闭；不接入任何业务接口。 */
const dialog = ref<HTMLDialogElement | null>(null);
/** 切换角色权限与用户角色两个设计区域。 */
const activeTab = ref<"roles" | "members">("roles");
/** 本次弹窗中正在查看的角色。 */
const selectedRoleId = ref("employee");
/** 本次弹窗中正在查看的示例用户。 */
const selectedMemberId = ref("lin");
/** 保存按钮只展示未提交提示，不制造真实保存成功的状态。 */
const saveHint = ref("");
/** 每次打开时重建示例角色，避免预览修改看起来已经永久保存。 */
function createRoles(): PreviewRole[] {
  return [
    { id: "employee", name: "普通用户", description: "使用已授权的业务系统与资料。", permissions: ["drive.enter", "archive.enter"] },
    { id: "archivist", name: "档案管理员", description: "负责档案业务办理和日常维护。", permissions: ["drive.enter", "archive.enter", "archive.manage"] },
    { id: "it-admin", name: "IT 管理员", description: "维护平台角色与授权，不默认获得全部业务资料的访问权。", permissions: ["drive.enter", "archive.enter", "platform.members", "platform.roles"] },
  ];
}
/** 每次打开时重建示例用户，角色分配不发送到后台。 */
function createMembers(): PreviewMember[] {
  return [
    { id: "lin", name: "林晓", department: "综合管理部", roleIds: ["employee"] },
    { id: "chen", name: "陈晨", department: "工程管理部", roleIds: ["employee", "archivist"] },
    { id: "admin", name: "管理员", department: "信息技术部", roleIds: ["it-admin"] },
  ];
}
/** 当前弹窗的角色编辑副本。 */
const roles = ref<PreviewRole[]>(createRoles());
/** 当前弹窗的用户角色编辑副本。 */
const members = ref<PreviewMember[]>(createMembers());
/** 根据本地列表选中项得到当前角色，缺省回到第一项。 */
const selectedRole = computed(() => roles.value.find((role) => role.id === selectedRoleId.value) ?? roles.value[0]!);
/** 根据本地列表选中项得到当前用户，缺省回到第一项。 */
const selectedMember = computed(() => members.value.find((member) => member.id === selectedMemberId.value) ?? members.value[0]!);
/** 权限分组仅表达页面排版；实际权限粒度后续按业务规则接入。 */
const permissionGroups = [
  { name: "公盘", icon: "folder", items: [{ id: "drive.enter", name: "进入公盘" }, { id: "drive.manage", name: "维护共享目录" }] },
  { name: "档案管理", icon: "archive", items: [{ id: "archive.enter", name: "进入档案管理" }, { id: "archive.manage", name: "办理档案业务" }] },
  { name: "平台管理", icon: "shield", items: [{ id: "platform.members", name: "管理用户角色" }, { id: "platform.roles", name: "配置角色权限" }] },
];

/** 打开完整设计稿并重置演示修改，不读取账号信息或改变实际权限。 */
function open() {
  roles.value = createRoles();
  members.value = createMembers();
  activeTab.value = "roles";
  selectedRoleId.value = "employee";
  selectedMemberId.value = "lin";
  saveHint.value = "";
  dialog.value?.showModal();
}
/** 关闭后由浏览器将焦点返回首页的管理入口。 */
function close() {
  dialog.value?.close();
}
/** 切换演示区域时清理上一次保存提示。 */
function switchTab(tab: "roles" | "members") {
  activeTab.value = tab;
  saveHint.value = "";
}
/** 只说明预览边界，不调用真实授权接口。 */
function previewSave() {
  saveHint.value = "当前为界面预览，设置尚未保存。";
}
/** 父组件只能打开设计稿，不能读取或持久化这里的编辑状态。 */
defineExpose({ open });
</script>

<template>
  <dialog ref="dialog" class="p-role-dialog" aria-labelledby="role-settings-title" aria-describedby="role-settings-intro" @click="(event) => { if (event.target === dialog) close(); }">
    <header class="p-role-heading"><span class="p-role-symbol"><Icon name="shield" :size="26" /></span><div><h2 id="role-settings-title">权限角色设置</h2><p id="role-settings-intro">统一管理平台角色、功能权限与用户授权。</p></div><button autofocus class="p-icon-button" aria-label="关闭权限角色设置" @click="close"><Icon name="close" /></button></header>
    <nav class="p-role-tabs" aria-label="切换权限设置区域"><button :class="{ active: activeTab === 'roles' }" :aria-pressed="activeTab === 'roles'" @click="switchTab('roles')"><Icon name="shield" :size="16" />角色权限</button><button :class="{ active: activeTab === 'members' }" :aria-pressed="activeTab === 'members'" @click="switchTab('members')"><Icon name="people" :size="17" />用户角色</button><span>IT 管理员</span></nav>
    <div class="p-role-body">
      <aside class="p-role-list" :aria-label="activeTab === 'roles' ? '角色列表' : '用户列表'">
        <template v-if="activeTab === 'roles'"><p>平台角色 <span>{{ roles.length }}</span></p><button v-for="role in roles" :key="role.id" :class="{ active: selectedRoleId === role.id }" :aria-pressed="selectedRoleId === role.id" @click="selectedRoleId = role.id; saveHint = ''"><span>{{ role.name }}</span><small>{{ members.filter((member) => member.roleIds.includes(role.id)).length }} 位示例用户</small></button></template>
        <template v-else><p>平台用户 <span>{{ members.length }}</span></p><button v-for="member in members" :key="member.id" :class="{ active: selectedMemberId === member.id }" :aria-pressed="selectedMemberId === member.id" @click="selectedMemberId = member.id; saveHint = ''"><span>{{ member.name }}</span><small>{{ member.department }}</small></button></template>
      </aside>
      <section v-if="activeTab === 'roles'" class="p-role-detail" aria-label="角色权限配置">
        <div class="p-role-detail-heading"><h3>{{ selectedRole.name }}</h3><span>示例角色</span></div><p class="p-role-description">{{ selectedRole.description }}</p>
        <fieldset v-for="group in permissionGroups" :key="group.name" class="p-permission-group"><legend><Icon :name="group.icon" :size="16" />{{ group.name }}</legend><div><label v-for="permission in group.items" :key="permission.id"><input v-model="selectedRole.permissions" type="checkbox" :value="permission.id" @change="saveHint = ''" /><span>{{ permission.name }}</span></label></div></fieldset>
        <p class="p-role-scope"><Icon name="lock" :size="14" />资料访问范围需按部门和业务授权单独配置。</p>
      </section>
      <section v-else class="p-role-detail" aria-label="用户角色分配">
        <div class="p-role-detail-heading"><h3>{{ selectedMember.name }}</h3><span>{{ selectedMember.department }}</span></div><p class="p-role-description">为该用户选择适用的角色，可分配多个角色。</p>
        <div class="p-member-roles"><label v-for="role in roles" :key="role.id"><input v-model="selectedMember.roleIds" type="checkbox" :value="role.id" @change="saveHint = ''" /><span><strong>{{ role.name }}</strong><small>{{ role.description }}</small></span></label></div>
      </section>
    </div>
    <footer class="p-role-footer"><div><p v-if="saveHint" role="status">{{ saveHint }}</p><span v-else>界面预览 · 修改不会影响实际账号权限</span></div><button class="p-role-cancel" @click="close">关闭</button><button class="p-role-save" @click="previewSave">保存设置</button></footer>
  </dialog>
</template>

<style scoped>
/* 管理设计稿使用宽弹窗，角色列表与配置区域分开阅读；背景页面保持当前位置。 */
.p-role-dialog { width: min(900px, calc(100% - 36px)); max-height: calc(100dvh - 36px); padding: 0; margin: auto; border: 1px solid #dce5df; border-radius: 16px; background: #fff; color: #30483b; box-shadow: 0 24px 90px #17342733; overflow: hidden; }
.p-role-dialog[open] { display: flex; flex-direction: column; }
.p-role-dialog::backdrop { background: #173d3052; backdrop-filter: blur(3px); }
.p-role-heading { display: flex; align-items: center; gap: 14px; padding: 26px 28px 23px; flex-shrink: 0; }
.p-role-symbol { display: grid; place-items: center; width: 50px; height: 50px; flex-shrink: 0; border-radius: 13px; background: #f0ecf7; color: #86749e; }
.p-role-heading h2 { font-size: 21px; font-weight: 600; }
.p-role-heading p { margin-top: 7px; font-size: 12px; line-height: 1.6; color: #849389; }
.p-role-heading > button { margin-left: auto; flex-shrink: 0; align-self: flex-start; }
.p-role-tabs { display: flex; align-items: center; gap: 26px; padding: 0 28px; border-bottom: 1px solid #e8eee9; flex-shrink: 0; }
.p-role-tabs button { display: inline-flex; align-items: center; gap: 8px; padding: 0 0 14px; border: 0; border-bottom: 2px solid transparent; background: none; color: #829388; font-size: 13px; }
.p-role-tabs button.active { color: #326b4e; border-color: #568e6b; font-weight: 600; }
.p-role-tabs > span { margin-left: auto; margin-bottom: 14px; font-size: 10px; color: #8e7da4; background: #f4f0f8; padding: 3px 7px; border-radius: 4px; }
.p-role-body { display: grid; grid-template-columns: 200px minmax(0, 1fr); flex: 1; min-height: 0; overflow-y: auto; }
.p-role-list { padding: 22px 14px; background: #f8faf8; border-right: 1px solid #e8eee9; }
.p-role-list > p { display: flex; justify-content: space-between; padding: 0 12px; margin-bottom: 14px; font-size: 11px; color: #8a9b8e; }
.p-role-list button { display: block; width: 100%; padding: 14px 12px; margin-bottom: 7px; text-align: left; border: 1px solid transparent; border-radius: 8px; background: none; font-size: 13px; color: #5c7062; }
.p-role-list button.active { background: #eaf3ed; border-color: #d7e7db; color: #34684a; }
.p-role-list button small { display: block; margin-top: 6px; font-size: 10px; color: #91a094; }
.p-role-detail { padding: 25px 28px; min-width: 0; }
.p-role-detail-heading { display: flex; align-items: center; gap: 12px; }
.p-role-detail-heading h3 { font-size: 17px; font-weight: 600; }
.p-role-detail-heading > span { font-size: 10px; color: #8d9a90; }
.p-role-description { color: #8b9a8e; font-size: 12px; line-height: 1.8; margin: 9px 0 24px; }
.p-permission-group { margin: 0 0 17px; padding: 14px 16px 16px; border: 1px solid #e4ebe5; border-radius: 9px; }
.p-permission-group legend { padding: 0 6px; color: #62816a; font-size: 12px; }
.p-permission-group legend .p-icon { margin-right: 7px; }
.p-permission-group > div { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.p-permission-group label { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #607563; cursor: pointer; }
.p-role-body input { accent-color: #56886a; width: 15px; height: 15px; flex-shrink: 0; margin: 0; }
.p-role-body input:focus-visible { outline: 2px solid #75b5a6; outline-offset: 3px; }
.p-role-scope { display: flex; align-items: flex-start; gap: 7px; font-size: 10px; color: #97a28e; line-height: 1.7; }
.p-member-roles { display: grid; gap: 12px; }
.p-member-roles label { display: flex; align-items: flex-start; gap: 11px; padding: 16px; border: 1px solid #e3ebe5; border-radius: 9px; cursor: pointer; }
.p-member-roles input { margin-top: 3px; }
.p-member-roles strong { display: block; font-size: 13px; font-weight: 500; }
.p-member-roles small { display: block; margin-top: 7px; font-size: 11px; line-height: 1.7; color: #8a9a8e; }
.p-role-footer { display: flex; align-items: center; gap: 10px; padding: 17px 28px; border-top: 1px solid #e8eee9; flex-shrink: 0; }
.p-role-footer > div { margin-right: auto; font-size: 10px; line-height: 1.6; color: #91a092; }
.p-role-footer p { color: #85754f; }
.p-role-cancel, .p-role-save { padding: 10px 16px; border: 1px solid #dbe5dd; border-radius: 7px; background: #fff; font-size: 12px; white-space: nowrap; }
.p-role-save { color: #fff; background: #407e5a; border-color: #407e5a; }
/* 手机端将角色选择移到配置区上方，列表自动换行，避免横向溢出。 */
@media (max-width: 600px) {
  .p-role-heading { padding: 22px 18px 19px; gap: 10px; }
  .p-role-heading h2 { font-size: 18px; }
  .p-role-heading p { font-size: 10px; }
  .p-role-symbol { width: 40px; height: 42px; }
  .p-role-heading > button { width: 26px; }
  .p-role-tabs { padding-inline: 18px; gap: 17px; }
  .p-role-tabs button { font-size: 12px; gap: 5px; }
  .p-role-tabs > span { font-size: 9px; padding-inline: 5px; }
  .p-role-body { display: block; }
  .p-role-list { display: flex; flex-wrap: wrap; gap: 8px; padding: 15px 18px; border-right: 0; border-bottom: 1px solid #e8eee9; }
  .p-role-list > p { width: 100%; padding: 0; margin: 0; }
  .p-role-list button { flex: 1 1 85px; width: auto; padding: 10px 7px; margin: 0; font-size: 11px; }
  .p-role-list button small { font-size: 9px; }
  .p-role-detail { padding: 20px 18px; }
  .p-role-description { font-size: 11px; margin-bottom: 20px; }
  .p-permission-group { padding: 12px 10px; }
  .p-permission-group > div { gap: 9px; }
  .p-permission-group label { font-size: 10px; gap: 5px; }
  .p-role-footer { flex-wrap: wrap; justify-content: flex-end; padding: 13px 18px; }
  .p-role-footer > div { width: 100%; }
}
</style>
