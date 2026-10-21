package org.rx.crawler.controller;

import org.rx.crawler.task.common.DebugSnapshots;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 调用方查看安智当前桌面。图片里可能有已登录页面，只走现有 Bearer 鉴权。
 */
@RestController
@RequestMapping("/api/v1/debug")
public class DebugViewController {
    @GetMapping(value = "/desktop", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> desktop() {
        byte[] png = DebugSnapshots.captureDesktopPng();
        if (png == null || png.length == 0) {
            return ResponseEntity.status(503).build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }
}
