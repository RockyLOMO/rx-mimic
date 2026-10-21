package org.rx.crawler;

import org.rx.crawler.task.common.ServiceInstanceLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "org.rx")
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        ServiceInstanceLock lock;
        try {
            lock = ServiceInstanceLock.acquire(ServiceInstanceLock.SERVICE_MUTEX);
        } catch (IllegalStateException alreadyRunning) {
            System.err.println(alreadyRunning.getMessage());
            System.exit(1);
            return;
        }
        // SpringApplication.run 在 Web 服务起来后就返回，进程靠非守护线程继续活着。
        // 锁必须留到进程退出，不能在 run 返回时释放。
        Runtime.getRuntime().addShutdownHook(new Thread(lock::close, "rx-mimic-service-lock"));
        SpringApplication application = new SpringApplication(Application.class);
        application.setHeadless(false);
        application.run(args);
    }
}
