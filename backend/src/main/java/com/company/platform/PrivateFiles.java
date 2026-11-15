package com.company.platform;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 本机开发邮件和独立账本的最小文件边界；Windows ACL / POSIX 权限仅授予当前运行人。 */
public final class PrivateFiles {
    private PrivateFiles() { }
    /** 必须为绝对目录；拒绝符号链接，避免把开发输出重定向到旧业务目录。 */
    public static Path directory(String configured) {
        try {
            Path path=Path.of(configured).normalize();
            if (!path.isAbsolute()) throw new IOException("absolute required");
            for (Path p=path; p!=null; p=p.getParent()) if (Files.isSymbolicLink(p)) throw new IOException("link rejected");
            Files.createDirectories(path); restrict(path); return path;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("独立私有目录配置不可用"); }
    }
    /** 取消继承的宽泛访问；不能设置权限时不继续保存秘密。 */
    public static void restrict(Path path) throws IOException {
        AclFileAttributeView acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
        if (acl!=null) {
            UserPrincipal owner=Files.getOwner(path);
            AclEntry.Builder entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if (Files.isDirectory(path)) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(entry.build()));
        } else Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"));
    }
}
