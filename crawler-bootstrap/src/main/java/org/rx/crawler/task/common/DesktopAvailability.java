package org.rx.crawler.task.common;

import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.springframework.stereotype.Service;

/** Fail closed on Windows: active WTS session plus unlocked Default input desktop. */
@Service
public class DesktopAvailability {
    interface Wts extends StdCallLibrary {
        boolean WTSQuerySessionInformationW(Pointer server, int session, int info,
                                           PointerByReference buffer, IntByReference bytes);
        void WTSFreeMemory(Pointer memory);
    }
    interface Desktop extends StdCallLibrary {
        Pointer OpenInputDesktop(int flags, boolean inherit, int access);
        boolean CloseDesktop(Pointer desktop);
        boolean GetUserObjectInformationW(Pointer object, int index, Pointer buffer, int length, IntByReference needed);
    }

    public boolean isAvailable() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
        }
        try {
            Wts wts = Native.load("Wtsapi32", Wts.class, W32APIOptions.UNICODE_OPTIONS);
            PointerByReference result = new PointerByReference();
            IntByReference length = new IntByReference();
            if (!wts.WTSQuerySessionInformationW(null, -1, 8, result, length)) { return false; }
            boolean active;
            try { active = length.getValue() >= 4 && result.getValue().getInt(0) == 0; }
            finally { wts.WTSFreeMemory(result.getValue()); }
            if (!active) { return false; }
            Desktop api = Native.load("user32", Desktop.class, W32APIOptions.UNICODE_OPTIONS);
            Pointer desktop = api.OpenInputDesktop(0, false, 1);
            if (desktop == null) { return false; }
            try {
                Memory name = new Memory(512);
                return api.GetUserObjectInformationW(desktop, 2, name, 512, length)
                        && "Default".equalsIgnoreCase(name.getWideString(0));
            } finally { api.CloseDesktop(desktop); }
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
