#!/bin/sh
set -e

KAFKA_CONTROLLER="${KAFKA_CONTROLLER:-kafka:9093}"
KAFKA_CONFIGS_BIN="/opt/kafka/bin/kafka-configs.sh"

echo "Esperando a que Kafka Controller acepte conexiones..."
until "$KAFKA_CONFIGS_BIN" --bootstrap-controller "$KAFKA_CONTROLLER" --describe --entity-type users > /dev/null 2>&1; do
  sleep 1
done

echo "Creando credenciales SCRAM..."
"$KAFKA_CONFIGS_BIN" --bootstrap-controller "$KAFKA_CONTROLLER" --alter --add-config 'SCRAM-SHA-256=[password=admin-secret]' --entity-type users --entity-name admin
"$KAFKA_CONFIGS_BIN" --bootstrap-controller "$KAFKA_CONTROLLER" --alter --add-config "SCRAM-SHA-256=[password=account-service-secret]" --entity-type users --entity-name account-service
"$KAFKA_CONFIGS_BIN" --bootstrap-controller "$KAFKA_CONTROLLER" --alter --add-config "SCRAM-SHA-256=[password=transfer-service-secret]" --entity-type users --entity-name transfer-service

echo "Usuarios SCRAM creados."
