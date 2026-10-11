package com.stockforge.notification.config;

import com.stockforge.notification.application.SendNotificationUseCase;
import com.stockforge.notification.domain.port.NotificationRecordRepository;
import com.stockforge.notification.domain.port.NotificationSender;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class NotificationUseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SendNotificationUseCase sendNotificationUseCase(
            NotificationSender sender, NotificationRecordRepository repository, Clock clock) {
        return new SendNotificationUseCase(sender, repository, clock);
    }
}
