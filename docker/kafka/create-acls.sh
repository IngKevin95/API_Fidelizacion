#!/bin/sh
set -e

KAFKA_HOST="${KAFKA_HOST:-kafka}:9092"
KAFKA_ACLS_BIN="/opt/kafka/bin/kafka-acls.sh"
COMMAND_CONFIG="/tmp/admin.properties"

cat > "$COMMAND_CONFIG" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-256
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="admin-secret";
EOF

# transfer-service: solo productor de debit-events/credit-events, solo consumidor de debit-results/credit-results/compensation-results
"$KAFKA_ACLS_BIN" --bootstrap-server "$KAFKA_HOST" --command-config "$COMMAND_CONFIG" \
  --add --allow-principal User:transfer-service \
  --operation Write --topic debit-events --topic credit-events --topic transfer-compensation

"$KAFKA_ACLS_BIN" --bootstrap-server "$KAFKA_HOST" --command-config "$COMMAND_CONFIG" \
  --add --allow-principal User:transfer-service \
  --operation Read --topic debit-results --topic credit-results --topic compensation-results --group transfer-service-debit-results --group transfer-service-credit-results --group transfer-service-compensation-results

# account-service: solo productor de debit-results/credit-results/compensation-results, solo consumidor de debit-events/credit-events/transfer-compensation
"$KAFKA_ACLS_BIN" --bootstrap-server "$KAFKA_HOST" --command-config "$COMMAND_CONFIG" \
  --add --allow-principal User:account-service \
  --operation Write --topic debit-results --topic credit-results --topic compensation-results

"$KAFKA_ACLS_BIN" --bootstrap-server "$KAFKA_HOST" --command-config "$COMMAND_CONFIG" \
  --add --allow-principal User:account-service \
  --operation Read --topic debit-events --topic credit-events --topic transfer-compensation --group account-service-debit --group account-service-credit --group account-service-compensation
