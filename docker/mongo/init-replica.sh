#!/bin/sh
set -e

echo "Esperando a que mongod acepte conexiones..."
until mongosh --host localhost --eval "print('ok')" > /dev/null 2>&1; do
  sleep 1
done

echo "Inicializando replica set rs0..."
mongosh --host localhost --eval '
  try {
    rs.status();
    print("Replica set ya inicializado.");
  } catch (e) {
    rs.initiate({
      _id: "rs0",
      members: [{ _id: 0, host: "mongo:27017" }]
    });
    print("Replica set inicializado.");
  }
'
