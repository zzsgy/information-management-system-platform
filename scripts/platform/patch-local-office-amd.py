"""固定ONLYOFFICE 10.0.0.169的未打包源码补丁；补齐加载依赖并重新生成gzip，保留原件。"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path

# 对应上游DocumentServer#3822；镜像升级后必须重新核对源码，不能沿用未知版本。
ROOT = Path("/var/www/onlyoffice/documentserver/web-apps/apps/common/main/lib")
BACKUP = Path("/var/lib/onlyoffice/ims-amd-originals-v1")
BASE_DEPENDENCY = "define(['common/main/lib/component/BaseView'],"
PATCHES = [
    ("component/ColorPaletteExt.js", "9d92f386284e2f226e9e24455eb70b7020edac0aec5c175a8d4e92633dc0145c", "define([],", BASE_DEPENDENCY),
    ("component/HSBColorPicker.js", "9025828cef1c1603073203d981e7b1a3883d990af11122d13d67b8d0d37dff2f", "define([],", BASE_DEPENDENCY),
    ("component/MaskedField.js", "d1b8f3efa10efc1d82b5f89f134628eda89e80957addfbbfeca7e74ba370a9ed", "define([],", BASE_DEPENDENCY),
    ("component/TextareaField.js", "25481253a99444a7dd82cb133edeb4f229fc10f251397a994a7a9ab5951bc5bd", "define([],", BASE_DEPENDENCY),
    # 放在模板参数之后，保持dlgTemplate仍为回调第一个参数。
    ("view/ExtendedColorDialog.js", "e9e1f2102e10a65858c929242261efbb0b778067c1b2ee1712ce400166213d29",
     "    'common/main/lib/component/HSBColorPicker',",
     "    'common/main/lib/component/Window',\n    'common/main/lib/component/HSBColorPicker',"),
    # Tip原版在基础tooltip到达前立即执行；使用AMD定义让依赖者也等待初始化完成。
    ("util/Tip.js", "347cc5e0be0ca1b0097e763f4f105f283726b382397ed392b4b1d158d3900c80",
     "// require(['common/main/lib/mods/tooltip'], function (t) {\n    tip(window.jQuery);\n// });",
     "define(['common/main/lib/mods/tooltip'], function () {\n    tip(window.jQuery);\n});"),
]


def replace_file(target, content):
    """同目录替换，避免Nginx读到半份源码或压缩文件。"""
    temporary = target.with_name(target.name + ".ims-next")
    temporary.write_bytes(content)
    temporary.chmod(target.stat().st_mode)
    os.replace(temporary, target)


def inspect_patch(relative, digest, before, after):
    """入口按完整摘要检查源文件；先检查全部目标，再开始修改。"""
    target = ROOT / relative
    current = target.read_bytes()
    before, after = before.encode(), after.encode()
    original = current if hashlib.sha256(current).hexdigest() == digest else current.replace(after, before, 1)
    if hashlib.sha256(original).hexdigest() != digest or original.count(before) != 1:
        raise RuntimeError("Office源码版本不匹配：" + relative)
    patched = original.replace(before, after, 1)
    if current not in (original, patched):
        raise RuntimeError("Office源码存在额外修改：" + relative)
    return target, original, patched


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restore", action="store_true", help="恢复备份的原生源码与gzip；操作前须关闭检查点")
    parser.add_argument("--check", action="store_true", help="只读检查补丁和gzip是否生效")
    args = parser.parse_args()
    if args.restore and args.check:
        parser.error("恢复与只读检查不能同时执行")
    plans = [inspect_patch(*item) for item in PATCHES]
    report = []
    for target, original, patched in plans:
        relative = target.relative_to(ROOT)
        compressed = target.with_suffix(".js.gz")
        desired = original if args.restore else patched
        if not args.check:
            saved = BACKUP / relative
            saved.parent.mkdir(parents=True, exist_ok=True)
            if not saved.exists():
                saved.write_bytes(original)
                saved.with_suffix(".js.gz").write_bytes(compressed.read_bytes())
            if target.read_bytes() != desired:
                replace_file(target, desired)
            # gzip是生成文件；只从目标源码重新生成，不手工修改压缩内容。
            packed = saved.with_suffix(".js.gz").read_bytes() if args.restore else gzip.compress(desired, mtime=0)
            if compressed.read_bytes() != packed:
                replace_file(compressed, packed)
        if target.read_bytes() != desired or gzip.decompress(compressed.read_bytes()) != desired:
            raise RuntimeError("Office源码或压缩副本未匹配：" + str(relative))
        report.append({"file": str(relative), "sha256": hashlib.sha256(desired).hexdigest()})
    print(json.dumps({"restored": args.restore, "checked": args.check, "files": report}))


if __name__ == "__main__":
    main()
