package org.rx.crawler.task.price;

public interface PriceAlertSender {
    boolean send(String body);
}
