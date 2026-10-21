package org.rx.crawler.service.impl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.rx.core.Strings;
import org.rx.crawler.service.Browser;
import org.rx.crawler.service.ConfigureScriptExecutor;
import org.rx.exception.InvalidException;

@RequiredArgsConstructor
public final class ApiConfigureScriptExecutor implements ConfigureScriptExecutor {
    @Getter
    private final Browser owner;

    @Override
    public String getConfigureScript(String scriptName) {
        return System.getProperty(scriptName, "");
    }

    @Override
    public void setConfigureScript(String scriptName, String scriptContent) {
        System.setProperty(scriptName, scriptContent);
    }

    @Override
    public <T> T execute(String scriptName, Object... args) {
        String scriptContent = getConfigureScript(scriptName);
        if (Strings.isEmpty(scriptContent)) {
            throw new InvalidException("ScriptName {} has empty content", scriptName);
        }
        return owner.executeScript(scriptContent, args);
    }
}
