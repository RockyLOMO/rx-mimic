package org.rx.crawler.task.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 只从密钥文件读取 Gmail SMTP 账号。调用方不得把密码写进日志。
 */
final class GmailKeysFile {
    private static final String[] KEY_NAMES = {"smtp_gmail2", "smtp_gmail1"};

    private GmailKeysFile() {
    }

    static List<Account> load(Path path) throws IOException {
        List<Account> accounts = new ArrayList<Account>();
        if (path == null || !Files.isRegularFile(path)) {
            return accounts;
        }
        JsonNode root = new ObjectMapper().readTree(path.toFile());
        for (String name : KEY_NAMES) {
            JsonNode node = root.get(name);
            if (node == null || !node.isObject()) {
                continue;
            }
            String username = text(node.get("usr"));
            String password = text(node.get("pwd")).replace(" ", "");
            if (username.isEmpty() || password.isEmpty()) {
                continue;
            }
            accounts.add(new Account(name, username, password));
        }
        return accounts;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    static final class Account {
        final String keyName;
        final String username;
        final String password;

        Account(String keyName, String username, String password) {
            this.keyName = keyName;
            this.username = username;
            this.password = password;
        }
    }
}
