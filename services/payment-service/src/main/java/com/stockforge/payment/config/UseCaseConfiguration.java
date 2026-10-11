package com.stockforge.payment.config;

import com.stockforge.payment.application.ProcessPaymentUseCase;
import com.stockforge.payment.application.RefundPaymentUseCase;
import com.stockforge.payment.domain.port.OutboxPublisher;
import com.stockforge.payment.domain.port.PaymentRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class UseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ProcessPaymentUseCase processPaymentUseCase(PaymentRepository payments, OutboxPublisher outbox, Clock clock) {
        return new ProcessPaymentUseCase(payments, outbox, clock);
    }

    @Bean
    RefundPaymentUseCase refundPaymentUseCase(PaymentRepository payments, OutboxPublisher outbox, Clock clock) {
        return new RefundPaymentUseCase(payments, outbox, clock);
    }
}
