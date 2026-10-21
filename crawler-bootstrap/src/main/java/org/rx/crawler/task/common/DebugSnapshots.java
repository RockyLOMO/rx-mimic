package org.rx.crawler.task.common;

import lombok.extern.slf4j.Slf4j;
import org.rx.crawler.service.Browser;

import javax.imageio.ImageIO;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * debug 打开时，每步除 HTML 外再存当前标签视口和整张桌面。
 * 视口图没有系统光标；桌面图能看到窗口位置和拖拽光标。
 */
@Slf4j
public final class DebugSnapshots {
    private DebugSnapshots() {
    }

    public static void writeViewport(Path dir, String stem, Browser browser) {
        if (dir == null || browser == null || stem == null) {
            return;
        }
        try {
            byte[] png = browser.captureViewportPng();
            writePng(dir.resolve(stem + ".png"), png);
        } catch (Exception e) {
            log.warn("Viewport snapshot fail, stem={}, error={}", stem, e.getMessage());
        }
    }

    public static void writeDesktop(Path dir, String stem) {
        if (dir == null || stem == null) {
            return;
        }
        try {
            writePng(dir.resolve(stem + ".desktop.png"), captureDesktopPng());
        } catch (Exception e) {
            log.warn("Desktop snapshot fail, stem={}, error={}", stem, e.getMessage());
        }
    }

    public static byte[] captureDesktopPng() {
        try {
            Rectangle bounds = desktopBounds();
            BufferedImage image = new Robot().createScreenCapture(bounds);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Desktop capture fail: {}", e.getMessage());
            return new byte[0];
        }
    }

    static void writePng(Path file, byte[] png) throws Exception {
        if (png == null || png.length == 0) {
            return;
        }
        Files.createDirectories(file.getParent());
        Files.write(file, png);
    }

    private static Rectangle desktopBounds() {
        Rectangle bounds = new Rectangle();
        GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (var device : environment.getScreenDevices()) {
            bounds = bounds.union(device.getDefaultConfiguration().getBounds());
        }
        if (bounds.isEmpty()) {
            bounds = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        }
        return bounds;
    }
}
