package com.stockforge.notification.config;

import com.stockforge.notification.application.NotificationTransactions;
import com.stockforge.notification.application.NotifyCustomerUseCase;
import com.stockforge.notification.domain.port.NotificationRepository;
import com.stockforge.notification.domain.port.NotificationSender;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root of the notification worker (ADR-004). */
@Configuration(proxyBeanMethods = false)
class NotificationUseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    NotificationTransactions notificationTransactions(NotificationRepository notifications) {
        return new NotificationTransactions(notifications);
    }

    @Bean
    NotifyCustomerUseCase notifyCustomerUseCase(
            NotificationTransactions transactions, NotificationSender sender, Clock clock) {
        return new NotifyCustomerUseCase(transactions, sender, UUID::randomUUID, clock);
    }
}
