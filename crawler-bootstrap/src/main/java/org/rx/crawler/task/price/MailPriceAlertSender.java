package org.rx.crawler.task.price;

import lombok.RequiredArgsConstructor;
import org.rx.crawler.task.common.MailLoginNotificationService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailPriceAlertSender implements PriceAlertSender {
    private final MailLoginNotificationService mail;

    @Override
    public boolean send(String body) {
        return mail.sendPlain(body);
    }
}
