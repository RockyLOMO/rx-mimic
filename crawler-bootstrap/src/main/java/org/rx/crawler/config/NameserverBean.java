package org.rx.crawler.config;

import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.IOC;
import org.rx.core.Strings;
import org.rx.exception.InvalidException;
import org.rx.net.nameserver.NameserverClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.nameserverEndpoints")
@NoArgsConstructor
public class NameserverBean {
    @Value("${spring.application.name}")
    String appName;
    @Value("${app.nameserverEndpoints:}")
    String nameserverEndpoints;

    NameserverClient client;

    public NameserverBean(String appName, String... endpoints) {
        this.appName = appName;
        this.nameserverEndpoints = endpoints != null ? String.join(",", endpoints) : null;
    }

    @SneakyThrows
    @PostConstruct
    public void init() {
        if (appName == null) {
            throw new InvalidException("appName is null");
        }
        if (Strings.isEmpty(nameserverEndpoints)) {
            return;
        }
        String[] endpoints = Strings.split(nameserverEndpoints, ",");
        client = new NameserverClient(appName);
        client.registerAsync(endpoints);
        IOC.register(NameserverBean.class, this);
        log.info("nsc register {} -> {}", appName, endpoints);
        client.waitInject(60 * 1000);
    }
}
