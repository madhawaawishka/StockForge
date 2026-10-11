package com.stockforge.order.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin endpoint for replaying dead-lettered events (spec §22). */
@RestController
@RequestMapping("/api/v1/admin/dlt")
class DltAdminController {

    private static final Logger log = LoggerFactory.getLogger(DltAdminController.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    DltAdminController(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    record DltReplayRequest(
            @NotBlank(message = "targetTopic is required") String targetTopic,
            String key,
            @NotBlank(message = "payload is required") String payload) {}

    record DltReplayResponse(String status, String targetTopic, String key) {}

    @PostMapping("/replay")
    ResponseEntity<DltReplayResponse> replay(@Valid @RequestBody DltReplayRequest request) {
        log.info("Admin initiated DLT replay to topic {} with key {}", request.targetTopic(), request.key());
        try {
            if (kafkaTemplate != null) {
                kafkaTemplate
                        .send(request.targetTopic(), request.key(), request.payload())
                        .get(5, TimeUnit.SECONDS);
            }
            return ResponseEntity.ok(new DltReplayResponse("REPLAYED", request.targetTopic(), request.key()));
        } catch (Exception e) {
            log.error("Failed replaying DLT message: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to replay DLT message", e);
        }
    }
}
