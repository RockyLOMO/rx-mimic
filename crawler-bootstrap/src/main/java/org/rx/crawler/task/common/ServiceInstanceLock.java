package org.rx.crawler.task.common;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

/**
 * 机器内只允许一个 rx-mimic 服务进程。锁活到进程退出，崩溃后由系统释放。
 * Global 命名空间跨登录会话，避免计划任务和 SSH 各起一个进程。
 */
public final class ServiceInstanceLock implements AutoCloseable {
    static final int ERROR_ALREADY_EXISTS = 183;
    public static final String SERVICE_MUTEX = "Global\\rx-mimic-service";

    private final WinNT.HANDLE handle;

    private ServiceInstanceLock(WinNT.HANDLE handle) {
        this.handle = handle;
    }

    public static ServiceInstanceLock acquire(String name) {
        if (!isWindows()) {
            return new ServiceInstanceLock(null);
        }
        WinNT.HANDLE created = Kernel32.INSTANCE.CreateMutex(null, false, name);
        int error = Native.getLastError();
        if (created == null) {
            throw new IllegalStateException("rx-mimic service lock failed, error=" + error);
        }
        if (error == ERROR_ALREADY_EXISTS) {
            Kernel32.INSTANCE.CloseHandle(created);
            throw new IllegalStateException("rx-mimic service is already running.");
        }
        return new ServiceInstanceLock(created);
    }

    @Override
    public void close() {
        if (handle != null) {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
