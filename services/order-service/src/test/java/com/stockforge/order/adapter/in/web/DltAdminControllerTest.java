package com.stockforge.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class DltAdminControllerTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private DltAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new DltAdminController(kafkaTemplate);
    }

    @Test
    void replaySuccessfullyRepublishesToTargetTopic() {
        CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        DltAdminController.DltReplayRequest request =
                new DltAdminController.DltReplayRequest("order-events", "key-1", "{\"orderId\":\"123\"}");

        ResponseEntity<DltAdminController.DltReplayResponse> response = controller.replay(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("REPLAYED");
        assertThat(response.getBody().targetTopic()).isEqualTo("order-events");

        verify(kafkaTemplate).send("order-events", "key-1", "{\"orderId\":\"123\"}");
    }
}
