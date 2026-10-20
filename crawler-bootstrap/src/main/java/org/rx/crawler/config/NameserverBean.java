package org.rx.crawler.config;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.rx.core.IOC;
import org.rx.core.Linq;
import org.rx.exception.InvalidException;
import org.rx.net.nameserver.NameserverClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class NameserverBean {
    @Component
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = {"apollo.bootstrap.enabled", "app.nameserverEnabled"}, havingValue = "true", matchIfMissing = true)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class ApolloLoader implements BeanPostProcessor {
        static final String NAMESPACE = "middleware";
        @Value("${spring.application.name}")
        String appName;
        String[] endpoints;

        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
            if (endpoints == null) {
                Linq<Config> configs = Linq.from(ConfigService.getAppConfig(), ConfigService.getConfig(NAMESPACE));
                endpoints = configs.select(p -> p.getArrayProperty("app.nameserverEndpoints", ",", null)).firstOrDefault(Objects::nonNull);
                if (endpoints != null) {
                    NameserverBean nsb = new NameserverBean(appName, endpoints);
                    nsb.init();
                    IOC.register(NameserverBean.class, nsb);
                }
            }
//            System.out.println(bean);
            return bean;
        }
    }

    final String appName;
    final String[] endpoints;
    NameserverClient client;

    @SneakyThrows
    @PostConstruct
    public void init() {
        if (appName == null) {
            throw new InvalidException("appName is null");
        }
        if (endpoints == null) {
            throw new InvalidException("nameserverEndpoints is null");
        }
        client = new NameserverClient(appName);
        client.registerAsync(endpoints);
        log.info("nsc register {} -> {}", appName, endpoints);
        client.waitInject(60 * 1000);
    }
}
