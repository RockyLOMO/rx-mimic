package org.rx.crawler.service.impl;

import com.microsoft.playwright.Page;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.Extends;
import org.rx.crawler.service.impl.HumanSlideTrack.Sample;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 用 SendInput 移动系统光标并按下拖动。Chrome 收到的是系统鼠标事件，
 * isTrusted、screenX、clientX、movementX 由系统填写。SetCursorPos 只改光标图，滑块收不到移动。
 */
@Slf4j
final class WindowsMouse {
    private static final int MOUSEEVENTF_MOVE = 0x0001;
    private static final int MOUSEEVENTF_LEFTDOWN = 0x0002;
    private static final int MOUSEEVENTF_LEFTUP = 0x0004;
    private static final int MOUSEEVENTF_ABSOLUTE = 0x8000;
    private static final int MOUSEEVENTF_VIRTUALDESK = 0x4000;

    private WindowsMouse() {
    }

    static boolean drag(Page page, double startX, double startY, double endX, double endY) {
        if (page == null || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        boolean pressed = false;
        boolean owned = false;
        try {
            HWND hwnd = focusChrome(page);
            if (hwnd == null) {
                log.info("System cursor drag skipped, Chrome window is not foreground");
                return false;
            }
            installProbe(page);
            PointerMap map = calibrate(page, startX, startY);
            moveTo(map, startX, startY);
            refine(page, map, startX, startY);
            double[] from = approachOrigin(page, startX, startY);
            moveTo(map, from[0], from[1]);
            Extends.sleep(ThreadLocalRandom.current().nextInt(50, 130));
            List<Sample> approach = HumanSlideTrack.approach(from[0], from[1], startX, startY,
                    ThreadLocalRandom.current());
            int approachMs = moveAlong(map, approach);
            log.info("System cursor approach, samples={}, durationMs={}, fromCss=({},{}), toCss=({},{})",
                    approach.size(), approachMs, round1(from[0]), round1(from[1]), round1(startX), round1(startY));
            Extends.sleep(ThreadLocalRandom.current().nextInt(80, 180));
            if (!moveTo(map, startX, startY, MOUSEEVENTF_LEFTDOWN)) {
                log.info("System cursor left button down failed, err={}", Native.getLastError());
                return false;
            }
            pressed = true;
            owned = true;
            double distance = Math.hypot(endX - startX, endY - startY);
            int duration = 0;
            int samples = 0;
            if (distance >= 3) {
                List<Sample> track = HumanSlideTrack.plan(startX, startY, endX, endY, ThreadLocalRandom.current());
                samples = track.size();
                for (int i = 0; i < track.size(); i++) {
                    Sample sample = track.get(i);
                    if (!moveTo(map, sample.x, sample.y)) {
                        break;
                    }
                    Extends.sleep(sample.delayMillis);
                    duration += sample.delayMillis;
                }
                // 拉回到终点后再按住一会儿，避免刚到头就松开。
                Extends.sleep(ThreadLocalRandom.current().nextInt(80, 170));
            } else {
                Extends.sleep(ThreadLocalRandom.current().nextInt(50, 130));
            }
            if (moveTo(map, endX, endY, MOUSEEVENTF_LEFTUP)) {
                pressed = false;
            }
            log.info("System cursor drag finished, samples={}, durationMs={}, calibrated={}, scale={},{}, fromCss=({},{}), toCss=({},{})",
                    samples, duration, map.calibrated, round1(map.scaleX), round1(map.scaleY),
                    round1(startX), round1(startY), round1(endX), round1(endY));
            return true;
        } catch (Exception e) {
            log.info("System cursor drag failed, fallback={}, error={}", !owned, e.toString());
            return owned;
        } finally {
            if (pressed) {
                sendMouse(MOUSEEVENTF_LEFTUP, 0, 0);
            }
            clearProbe(page);
        }
    }

    private static HWND focusChrome(Page page) {
        page.bringToFront();
        Extends.sleep(220);
        HWND hwnd = User32.INSTANCE.GetForegroundWindow();
        if (!isChrome(hwnd)) {
            hwnd = findByTitle(page);
        }
        if (!isChrome(hwnd)) {
            return null;
        }
        forceForeground(hwnd);
        Extends.sleep(120);
        if (!sameWindow(hwnd, User32.INSTANCE.GetForegroundWindow())) {
            log.info("System cursor drag skipped, Chrome did not stay foreground");
            return null;
        }
        return hwnd;
    }

    private static void forceForeground(HWND hwnd) {
        HWND current = User32.INSTANCE.GetForegroundWindow();
        if (sameWindow(hwnd, current)) {
            return;
        }
        WinDef.DWORD targetThread = new WinDef.DWORD(User32.INSTANCE.GetWindowThreadProcessId(hwnd, null));
        WinDef.DWORD foreThread = new WinDef.DWORD(User32.INSTANCE.GetWindowThreadProcessId(current, null));
        WinDef.DWORD thisThread = new WinDef.DWORD(Kernel32.INSTANCE.GetCurrentThreadId() & 0xffffffffL);
        boolean attachedFore = false;
        boolean attachedTarget = false;
        try {
            if (foreThread.longValue() != thisThread.longValue()) {
                attachedFore = User32.INSTANCE.AttachThreadInput(thisThread, foreThread, true);
            }
            if (targetThread.longValue() != thisThread.longValue()
                    && targetThread.longValue() != foreThread.longValue()) {
                attachedTarget = User32.INSTANCE.AttachThreadInput(thisThread, targetThread, true);
            }
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_SHOW);
            User32.INSTANCE.SetForegroundWindow(hwnd);
        } finally {
            if (attachedTarget) {
                User32.INSTANCE.AttachThreadInput(thisThread, targetThread, false);
            }
            if (attachedFore) {
                User32.INSTANCE.AttachThreadInput(thisThread, foreThread, false);
            }
        }
    }

    private static HWND findByTitle(Page page) {
        String title;
        try {
            title = page.title();
        } catch (Exception e) {
            return null;
        }
        if (title == null) {
            return null;
        }
        final String expect = title.trim();
        if (expect.length() < 2) {
            return null;
        }
        final HWND[] found = new HWND[1];
        User32.INSTANCE.EnumWindows(new WinUser.WNDENUMPROC() {
            @Override
            public boolean callback(HWND hwnd, Pointer data) {
                if (!isChrome(hwnd) || !User32.INSTANCE.IsWindowVisible(hwnd)) {
                    return true;
                }
                char[] buffer = new char[512];
                int length = User32.INSTANCE.GetWindowText(hwnd, buffer, buffer.length);
                if (length <= 0) {
                    return true;
                }
                String windowTitle = new String(buffer, 0, length);
                if (!windowTitle.contains(expect)) {
                    return true;
                }
                WinDef.RECT rect = new WinDef.RECT();
                if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) {
                    return true;
                }
                if (rect.right - rect.left < 400 || rect.bottom - rect.top < 300) {
                    return true;
                }
                found[0] = hwnd;
                return false;
            }
        }, null);
        return found[0];
    }

    private static boolean isChrome(HWND hwnd) {
        if (hwnd == null) {
            return false;
        }
        char[] buffer = new char[256];
        User32.INSTANCE.GetClassName(hwnd, buffer, buffer.length);
        int end = 0;
        while (end < buffer.length && buffer[end] != '\0') {
            end++;
        }
        return "Chrome_WidgetWin_1".equals(new String(buffer, 0, end));
    }

    private static boolean sameWindow(HWND left, HWND right) {
        if (left == null || right == null || left.getPointer() == null || right.getPointer() == null) {
            return false;
        }
        return Pointer.nativeValue(left.getPointer()) == Pointer.nativeValue(right.getPointer());
    }

    private static PointerMap calibrate(Page page, double cssX, double cssY) {
        PointerMap geometric = geometricMap(page);
        PointerMap learned = twoPoint(page, geometric.toX(cssX), geometric.toY(cssY));
        if (learned != null) {
            learned.calibrated = true;
            return learned;
        }
        PointerMap plain = new PointerMap();
        plain.originX = geometric.originX / Math.max(0.5, geometric.scaleX);
        plain.originY = geometric.originY / Math.max(0.5, geometric.scaleY);
        plain.scaleX = 1;
        plain.scaleY = 1;
        learned = twoPoint(page, plain.toX(cssX), plain.toY(cssY));
        if (learned != null) {
            learned.calibrated = true;
            return learned;
        }
        geometric.calibrated = false;
        return geometric;
    }

    private static PointerMap geometricMap(Page page) {
        PointerMap map = new PointerMap();
        Map<String, Object> metrics = evalMap(page, "() => ({sx:window.screenX||0,sy:window.screenY||0,"
                + "ow:window.outerWidth||0,oh:window.outerHeight||0,iw:window.innerWidth||0,ih:window.innerHeight||0,"
                + "dpr:window.devicePixelRatio||1})");
        double screenX = metric(metrics, "sx");
        double screenY = metric(metrics, "sy");
        double outerW = metric(metrics, "ow");
        double outerH = metric(metrics, "oh");
        double innerW = metric(metrics, "iw");
        double innerH = metric(metrics, "ih");
        double dpr = metric(metrics, "dpr");
        if (dpr < 0.5) {
            dpr = 1;
        }
        double borderX = Math.max(0, (outerW - innerW) / 2.0);
        double topChrome = Math.max(0, (outerH - innerH) - borderX);
        map.originX = (screenX + borderX) * dpr;
        map.originY = (screenY + topChrome) * dpr;
        map.scaleX = dpr;
        map.scaleY = dpr;
        return map;
    }

    private static PointerMap twoPoint(Page page, int guessX, int guessY) {
        sendAbsolute(guessX - 36, guessY, 0);
        Extends.sleep(40);
        double[] firstClient = new double[2];
        WinDef.POINT firstCursor = new WinDef.POINT();
        if (!sample(page, guessX, guessY, firstClient, firstCursor)) {
            return null;
        }
        double[] secondClient = new double[2];
        WinDef.POINT secondCursor = new WinDef.POINT();
        if (!sample(page, guessX + 72, guessY + 28, secondClient, secondCursor)) {
            return null;
        }
        double dcx = secondClient[0] - firstClient[0];
        double dcy = secondClient[1] - firstClient[1];
        if (Math.abs(dcx) < 8 || Math.abs(dcy) < 4) {
            return null;
        }
        PointerMap map = new PointerMap();
        map.scaleX = (secondCursor.x - firstCursor.x) / dcx;
        map.scaleY = (secondCursor.y - firstCursor.y) / dcy;
        if (map.scaleX < 0.6 || map.scaleX > 2.5 || map.scaleY < 0.6 || map.scaleY > 2.5) {
            return null;
        }
        map.originX = firstCursor.x - firstClient[0] * map.scaleX;
        map.originY = firstCursor.y - firstClient[1] * map.scaleY;
        return map;
    }

    private static void refine(Page page, PointerMap map, double cssX, double cssY) {
        for (int i = 0; i < 2; i++) {
            double[] client = new double[2];
            WinDef.POINT cursor = new WinDef.POINT();
            if (!sample(page, map.toX(cssX), map.toY(cssY), client, cursor)) {
                return;
            }
            double errX = client[0] - cssX;
            double errY = client[1] - cssY;
            if (Math.abs(errX) < 1.5 && Math.abs(errY) < 1.5) {
                map.calibrated = true;
                return;
            }
            map.originX -= errX * map.scaleX;
            map.originY -= errY * map.scaleY;
            map.calibrated = true;
        }
    }

    private static boolean sample(Page page, int x, int y, double[] clientOut, WinDef.POINT cursorOut) {
        try {
            page.evaluate("() => { window.__rxMm = null; }");
        } catch (Exception e) {
            return false;
        }
        if (!sendAbsolute(x, y, 0)) {
            return false;
        }
        long deadline = System.currentTimeMillis() + 350;
        while (System.currentTimeMillis() < deadline) {
            double[] client = readSample(page);
            if (client != null) {
                WinDef.POINT cursor = new WinDef.POINT();
                if (!User32.INSTANCE.GetCursorPos(cursor)) {
                    return false;
                }
                clientOut[0] = client[0];
                clientOut[1] = client[1];
                cursorOut.x = cursor.x;
                cursorOut.y = cursor.y;
                return true;
            }
            Extends.sleep(20);
        }
        return false;
    }

    private static double[] readSample(Page page) {
        Object value;
        try {
            value = page.evaluate("() => window.__rxMm || null");
        } catch (Exception e) {
            return null;
        }
        if (!(value instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        if (!map.containsKey("x") || !map.containsKey("y")) {
            return null;
        }
        double x = metric(map, "x");
        double y = metric(map, "y");
        return new double[]{x, y};
    }

    private static void installProbe(Page page) {
        page.evaluate("() => {"
                + "if (window.__rxMmFn) { window.removeEventListener('mousemove', window.__rxMmFn, true); }"
                + "window.__rxMm = null;"
                + "window.__rxMmFn = function(e){ window.__rxMm = {x:e.clientX, y:e.clientY}; };"
                + "window.addEventListener('mousemove', window.__rxMmFn, true);"
                + "}");
    }

    private static void clearProbe(Page page) {
        if (page == null) {
            return;
        }
        try {
            page.evaluate("() => {"
                    + "if (window.__rxMmFn) { window.removeEventListener('mousemove', window.__rxMmFn, true); window.__rxMmFn = null; }"
                    + "window.__rxMm = null;"
                    + "}");
        } catch (Exception ignored) {
            // 页面可能已经跳走
        }
    }

    private static int moveAlong(PointerMap map, List<Sample> track) {
        int duration = 0;
        if (track == null) {
            return 0;
        }
        for (int i = 0; i < track.size(); i++) {
            Sample sample = track.get(i);
            if (!moveTo(map, sample.x, sample.y)) {
                break;
            }
            Extends.sleep(sample.delayMillis);
            duration += sample.delayMillis;
        }
        return duration;
    }

    /**
     * 视口里离滑块起点足够远的随机点。校准完光标会停在滑块上，先落到这里再慢慢移过去。
     */
    private static double[] approachOrigin(Page page, double startX, double startY) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double width = 1280;
        double height = 720;
        try {
            Map<String, Object> metrics = evalMap(page, "() => ({w:window.innerWidth||0,h:window.innerHeight||0})");
            double w = metric(metrics, "w");
            double h = metric(metrics, "h");
            if (w > 200) {
                width = w;
            }
            if (h > 200) {
                height = h;
            }
        } catch (Exception ignored) {
            // 页面跳走时用默认视口
        }
        for (int i = 0; i < 8; i++) {
            double x = 30 + random.nextDouble() * Math.max(40, width - 60);
            double y = 30 + random.nextDouble() * Math.max(40, height - 60);
            if (Math.hypot(x - startX, y - startY) >= 160) {
                return new double[]{x, y};
            }
        }
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = 200 + random.nextDouble() * 220;
        double x = startX + Math.cos(angle) * dist;
        double y = startY + Math.sin(angle) * dist;
        if (x < 20) {
            x = 20;
        } else if (x > width - 20) {
            x = width - 20;
        }
        if (y < 20) {
            y = 20;
        } else if (y > height - 20) {
            y = height - 20;
        }
        return new double[]{x, y};
    }

    private static boolean moveTo(PointerMap map, double cssX, double cssY) {
        return moveTo(map, cssX, cssY, 0);
    }

    private static boolean moveTo(PointerMap map, double cssX, double cssY, int buttonFlag) {
        return sendAbsolute(map.toX(cssX), map.toY(cssY), buttonFlag);
    }

    private static boolean sendAbsolute(int x, int y, int buttonFlag) {
        int vx = User32.INSTANCE.GetSystemMetrics(WinUser.SM_XVIRTUALSCREEN);
        int vy = User32.INSTANCE.GetSystemMetrics(WinUser.SM_YVIRTUALSCREEN);
        int vw = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXVIRTUALSCREEN);
        int vh = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CYVIRTUALSCREEN);
        if (vw <= 1 || vh <= 1) {
            return false;
        }
        int ax = (int) Math.round((x - vx) * 65535.0 / vw);
        int ay = (int) Math.round((y - vy) * 65535.0 / vh);
        if (ax < 0) {
            ax = 0;
        } else if (ax > 65535) {
            ax = 65535;
        }
        if (ay < 0) {
            ay = 0;
        } else if (ay > 65535) {
            ay = 65535;
        }
        return sendMouse(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK | buttonFlag, ax, ay);
    }

    private static boolean sendMouse(int flags, int dx, int dy) {
        WinUser.INPUT input = new WinUser.INPUT();
        input.type = new WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE);
        input.input.setType("mi");
        input.input.mi.dx = new WinDef.LONG(dx);
        input.input.mi.dy = new WinDef.LONG(dy);
        input.input.mi.mouseData = new WinDef.DWORD(0);
        input.input.mi.dwFlags = new WinDef.DWORD(flags);
        input.input.mi.time = new WinDef.DWORD(0);
        input.input.mi.dwExtraInfo = new BaseTSD.ULONG_PTR(0);
        input.input.write();
        input.write();
        WinDef.DWORD sent = User32.INSTANCE.SendInput(new WinDef.DWORD(1), new WinUser.INPUT[]{input}, input.size());
        return sent != null && sent.longValue() == 1;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evalMap(Page page, String script) {
        Object value = page.evaluate(script);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    private static double metric(Map<?, ?> values, String key) {
        if (values == null) {
            return 0;
        }
        Object value = values.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static final class PointerMap {
        private double originX;
        private double originY;
        private double scaleX = 1;
        private double scaleY = 1;
        private boolean calibrated;

        private int toX(double css) {
            return (int) Math.round(originX + css * scaleX);
        }

        private int toY(double css) {
            return (int) Math.round(originY + css * scaleY);
        }
    }
}
