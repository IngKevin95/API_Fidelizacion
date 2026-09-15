package com.loyalty.e2e;

import com.loyalty.e2e.support.ApiClient;
import com.loyalty.e2e.support.AuthTestFixture;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

class IdempotencyE2ETest {

    @Test
    void resendingSameDebitEventDoesNotDebitTwice() throws Exception {
        AuthTestFixture.RegisteredUser user = AuthTestFixture.registerAndLogin("idempotency");
        String token = "Bearer " + user.accessToken();

        String sourceAccountId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"balance\": 100}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        String targetAccountId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"balance\": 0}")
                .post("/accounts")
                .then().statusCode(201)
                .extract().path("id");

        String transactionId = ApiClient.gateway()
                .header("Authorization", token)
                .body("{\"sourceAccountId\":\"" + sourceAccountId + "\",\"targetAccountId\":\"" + targetAccountId + "\",\"amount\":40}")
                .post("/api/v1/points/transfer")
                .then().statusCode(202)
                .extract().path("transactionId");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                ApiClient.gateway()
                        .header("Authorization", token)
                        .get("/accounts/" + sourceAccountId)
                        .then().statusCode(200)
                        .body("balance", equalTo(60)));

        String duplicateDebitEvent = "{\"transactionId\":\"" + transactionId + "\",\"sourceAccountId\":\""
                + sourceAccountId + "\",\"amount\":40,\"timestamp\":\"2026-01-01T00:00:00Z\"}";

        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9094");
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
            producer.send(new ProducerRecord<>("debit-events", transactionId, duplicateDebitEvent)).get();
        }

        Thread.sleep(3000);

        ApiClient.gateway()
                .header("Authorization", token)
                .get("/accounts/" + sourceAccountId)
                .then().statusCode(200)
                .body("balance", equalTo(60));
    }
}
