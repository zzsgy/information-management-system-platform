-- 原件和派生PDF分开引用，原版本的权限或副本异常不能借派生文件绕过。
ALTER TABLE storage.preview_session ADD COLUMN render_object_id uuid REFERENCES storage.object(id);
COMMENT ON COLUMN storage.preview_session.render_object_id IS 'Office受控预览内部派生PDF；原PDF和图片直接使用object_id，不对外提供该对象下载地址';
