package org.rx.crawler.task.jd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.rx.core.Strings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 京东联盟登录账号只从密钥文件读取。文件不进仓库，密码不进日志。
 * 支持根对象 {@code usr/pwd}，或包在 {@code jd_union} 下。
 */
public final class JdLoginKeys {
    private JdLoginKeys() {
    }

    public static Credentials load(String path) {
        if (Strings.isEmpty(path)) {
            return null;
        }
        return load(Paths.get(path));
    }

    public static Credentials load(Path path) {
        try {
            if (path == null || !Files.isRegularFile(path)) {
                return null;
            }
            JsonNode root = new ObjectMapper().readTree(path.toFile());
            JsonNode node = root.get("jd_union");
            if (node == null || !node.isObject()) {
                node = root;
            }
            String username = text(node.get("usr"));
            String password = text(node.get("pwd"));
            if (username.isEmpty() || password.isEmpty()) {
                return null;
            }
            return new Credentials(username, password);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    public static final class Credentials {
        private final String username;
        private final String password;

        Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }
    }
}
