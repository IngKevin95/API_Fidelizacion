#!/bin/sh
set -e

echo "Esperando a que Kafka acepte conexiones..."
until kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do
  sleep 1
done

for topic in debit-events credit-events transfer-compensation; do
  echo "Creando topic: $topic"
  kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1
done

echo "Topics creados:"
kafka-topics.sh --bootstrap-server localhost:9092 --list