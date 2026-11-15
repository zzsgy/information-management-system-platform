package com.company.platform.storage;
import java.nio.file.Path;
/** 检测进程适配边界；禁止用默认允许实现替代真实检测。 */
public interface FileScanEngine {
    /** 对固定隔离文件执行检测，错误和无法判定均不得返回 CLEAN。 */
    ClamFileScanner.Verdict scan(Path source);
}
