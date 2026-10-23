package org.rx.crawler.task.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

@Component
@RequiredArgsConstructor
public class ResultWriter {
    private final ObjectMapper objectMapper;

    public void appendJsonLine(String outputPath, Object value) {
        if (Strings.isEmpty(outputPath) || value == null) {
            return;
        }
        try {
            Path path = Paths.get(outputPath);
            Path parent = path.getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(value);
            String type = node.path("taskType").asText("");
            if (value instanceof PromotionUrlResult || org.rx.crawler.task.price.UnionPersistencePolicy.unionAction(type)
                    || value instanceof org.rx.crawler.task.jd.JdUnionProductSearchResult
                    || value instanceof org.rx.crawler.task.tb.TbUnionProductSearchResult) {
                int platform = value instanceof org.rx.crawler.task.tb.TbUnionProductSearchResult || type.startsWith("getTb")
                        ? org.rx.crawler.task.price.PriceCodes.TB : org.rx.crawler.task.price.PriceCodes.JD;
                node = org.rx.crawler.task.price.UnionPersistencePolicy.project(node, platform);
            }
            String line = objectMapper.writeValueAsString(node) + System.lineSeparator();
            java.nio.file.Files.write(path, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new InvalidException("Write crawl result fail, path={}", outputPath, e);
        }
    }
}
