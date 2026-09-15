#!/bin/sh
set -e

KAFKA_HOST="${KAFKA_HOST:-kafka}:9092"
KAFKA_TOPICS_BIN="/opt/kafka/bin/kafka-topics.sh"

echo "Esperando a que Kafka acepte conexiones..."
until "$KAFKA_TOPICS_BIN" --bootstrap-server "$KAFKA_HOST" --list > /dev/null 2>&1; do
  sleep 1
done

for topic in debit-events credit-events transfer-compensation; do
  echo "Creando topic: $topic"
  "$KAFKA_TOPICS_BIN" --bootstrap-server "$KAFKA_HOST" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1
done

echo "Topics creados:"
"$KAFKA_TOPICS_BIN" --bootstrap-server "$KAFKA_HOST" --list
