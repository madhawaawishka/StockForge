package com.stockforge.payment.config;

import com.stockforge.payment.adapter.out.id.UuidV7Generator;
import com.stockforge.payment.application.PaymentTransactions;
import com.stockforge.payment.application.ProcessPaymentUseCase;
import com.stockforge.payment.application.RefundPaymentUseCase;
import com.stockforge.payment.domain.port.IdGenerator;
import com.stockforge.payment.domain.port.OutboxPublisher;
import com.stockforge.payment.domain.port.PaymentProvider;
import com.stockforge.payment.domain.port.PaymentRepository;
import com.stockforge.payment.domain.port.ProcessedMessages;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Composition root of the payment service (ADR-004). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
class UseCaseConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator(Clock clock) {
        return new UuidV7Generator(clock);
    }

    @Bean
    PaymentTransactions paymentTransactions(
            PaymentRepository payments, OutboxPublisher outbox, ProcessedMessages inbox, Clock clock) {
        return new PaymentTransactions(payments, outbox, inbox, clock);
    }

    @Bean
    ProcessPaymentUseCase processPaymentUseCase(
            PaymentTransactions transactions, PaymentProvider provider, IdGenerator ids, Clock clock) {
        return new ProcessPaymentUseCase(transactions, provider, ids, clock);
    }

    @Bean
    RefundPaymentUseCase refundPaymentUseCase(
            PaymentRepository payments, PaymentTransactions transactions, PaymentProvider provider) {
        return new RefundPaymentUseCase(payments, transactions, provider);
    }
}
