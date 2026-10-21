package org.rx.crawler.task.tb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.rx.core.Strings;
import org.rx.crawler.task.common.CustomCrawlStatus;
import org.rx.crawler.task.common.PromotionUrlResult;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;

/**
 * 淘宝商品详情和转链的上一次成功结果。失败响应本身保持失败并且业务字段为空，
 * 上次成功放在 fallback。
 */
public final class TbLastResultStore {
    private final ObjectMapper objectMapper;
    private final Path root;

    public TbLastResultStore(ObjectMapper objectMapper, Path root) {
        this.objectMapper = objectMapper;
        this.root = root;
    }

    public void finishDetail(TbProductDetailResult result) {
        if (result == null) {
            return;
        }
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            saveDetail(result);
            result.setFallback(null);
            return;
        }
        TbProductDetailResult previous = read(detailFile(result.getItemId()), TbProductDetailResult.class);
        clearDetail(result);
        result.setFallback(previous);
    }

    public void finishPromotion(PromotionUrlResult result) {
        if (result == null) {
            return;
        }
        if (result.getStatus() == CustomCrawlStatus.SUCCESS) {
            savePromotion(result);
            result.setFallback(null);
            return;
        }
        PromotionUrlResult previous = readPromotion(result.getKeyword(), result.getItemId(), result.getMktItemId());
        clearPromotion(result);
        result.setFallback(previous);
    }

    private void saveDetail(TbProductDetailResult result) {
        Path file = detailFile(result.getItemId());
        if (file == null) {
            return;
        }
        TbProductDetailResult copy = copy(result, TbProductDetailResult.class);
        if (copy == null) {
            return;
        }
        copy.setFallback(null);
        copy.setMessage(null);
        copy.setCurrentUrl(null);
        copy.setProfileName(null);
        copy.setDiagnostics(new LinkedHashMap<String, Object>());
        stripImages(copy.getMainImages());
        stripImages(copy.getDetailImages());
        write(file, copy);
    }

    private void savePromotion(PromotionUrlResult result) {
        PromotionUrlResult copy = copy(result, PromotionUrlResult.class);
        if (copy == null) {
            return;
        }
        copy.setFallback(null);
        copy.setMessage(null);
        copy.setCurrentUrl(null);
        copy.setProfileName(null);
        copy.setDiagnostics(new LinkedHashMap<String, Object>());
        write(promotionFile(result.getItemId()), copy);
        write(promotionFile(result.getMktItemId()), copy);
        write(promotionKeywordFile(result.getKeyword()), copy);
    }

    private PromotionUrlResult readPromotion(String keyword, String itemId, String mktItemId) {
        PromotionUrlResult byItem = read(promotionFile(itemId), PromotionUrlResult.class);
        if (byItem != null) {
            return byItem;
        }
        PromotionUrlResult byMkt = read(promotionFile(mktItemId), PromotionUrlResult.class);
        if (byMkt != null) {
            return byMkt;
        }
        PromotionUrlResult byKeywordId = read(promotionFile(keyword), PromotionUrlResult.class);
        if (byKeywordId != null) {
            return byKeywordId;
        }
        return read(promotionKeywordFile(keyword), PromotionUrlResult.class);
    }

    private void clearDetail(TbProductDetailResult result) {
        result.setTitle("");
        result.setPrice("");
        result.setShopName("");
        result.setMainImages(new ArrayList<TbImageInfo>());
        result.setDetailImages(new ArrayList<TbImageInfo>());
        result.setSpecs(new ArrayList<TbProductSpecItem>());
    }

    private void clearPromotion(PromotionUrlResult result) {
        result.setItemId(null);
        result.setMktItemId(null);
        result.setProductInfo(null);
        result.setPromotionUrl(null);
    }

    private Path detailFile(String itemId) {
        if (itemId == null || !itemId.trim().matches("\\d+")) {
            return null;
        }
        return root.resolve("detail").resolve(itemId.trim() + ".json");
    }

    private Path promotionFile(String id) {
        String safe = safeId(id);
        if (safe.isEmpty()) {
            return null;
        }
        return root.resolve("promotion").resolve(safe + ".json");
    }

    private Path promotionKeywordFile(String keyword) {
        if (Strings.isEmpty(keyword)) {
            return null;
        }
        return root.resolve("promotion").resolve("kw-" + sha256(keyword.trim()) + ".json");
    }

    private static String safeId(String id) {
        if (id == null) {
            return "";
        }
        String trimmed = id.trim();
        return trimmed.matches("[A-Za-z0-9_-]{1,128}") ? trimmed : "";
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void stripImages(java.util.List<TbImageInfo> images) {
        if (images == null) {
            return;
        }
        for (TbImageInfo image : images) {
            if (image != null) {
                image.setLocalPath(null);
                image.setFileName(null);
            }
        }
    }

    private <T> T copy(Object value, Class<T> type) {
        try {
            return objectMapper.convertValue(value, type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private <T> T read(Path file, Class<T> type) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            return null;
        }
    }

    private void write(Path file, Object value) {
        if (file == null || value == null) {
            return;
        }
        Path temporary = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 留下的临时文件不影响下一次写入。
            }
        }
    }
}
