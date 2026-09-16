#!/bin/sh
set -e

KAFKA_HOST="${KAFKA_HOST:-kafka}:9092"
KAFKA_TOPICS_BIN="/opt/kafka/bin/kafka-topics.sh"
COMMAND_CONFIG="/tmp/admin.properties"

cat > "$COMMAND_CONFIG" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-256
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="admin-secret";
EOF

echo "Esperando a que Kafka acepte conexiones..."
until "$KAFKA_TOPICS_BIN" --bootstrap-server "$KAFKA_HOST" --command-config "$COMMAND_CONFIG" --list > /dev/null 2>&1; do
  sleep 1
done

for topic in debit-events credit-events transfer-compensation debit-results credit-results compensation-results; do
  echo "Creando topic: $topic"
  "$KAFKA_TOPICS_BIN" --bootstrap-server "$KAFKA_HOST" --command-config "$COMMAND_CONFIG" \
    --create --if-not-exists \
    --topic "$topic" \
    --partitions 3 \
    --replication-factor 1
done

echo "Topics creados:"
"$KAFKA_TOPICS_BIN" --bootstrap-server "$KAFKA_HOST" --command-config "$COMMAND_CONFIG" --list
