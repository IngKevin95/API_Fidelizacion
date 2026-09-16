# Guía de Pruebas — Loyalty Microservice Platform

Esta guía explica qué es el sistema, cómo está armado, y cómo probarlo paso a paso desde cero, sin dar nada por sabido.

## 1. Qué es esto

Una plataforma de fidelización de puntos: los usuarios se registran, crean cuentas, y transfieren puntos entre cuentas. Está construida como **6 microservicios** que se comunican entre sí, no como una sola aplicación.

| Servicio | Qué hace | Alcanzable desde tu máquina |
|---|---|---|
| `eureka-server` | "Directorio telefónico" — sabe qué servicios existen y dónde están | No (solo red interna de Docker) |
| `account-service` | Dueño de las cuentas: crear, consultar, bloquear, ajustar límites, ver el kardex de auditoría | No (solo red interna de Docker) |
| `transfer-service` | Recibe la orden de transferir puntos y orquesta el proceso | No (solo red interna de Docker) |
| `auth-service` | Registro y login de usuarios (habla con Keycloak) | No (solo red interna de Docker) |
| `gateway` | Puerta de entrada única — todo pasa por aquí | Sí, `http://localhost:8080` |
| `keycloak` | Servidor de identidad (registro/login reales) | Sí, consola admin en `http://localhost:8180` |
| (infraestructura) | MongoDB, Kafka — no son "tuyos", son piezas de terceros que el sistema usa | No (solo red interna de Docker) |

**Regla de oro: nunca le hablas a `account-service`, `transfer-service` ni `auth-service` directamente. Todo pasa por el `gateway` (puerto 8080).** Desde la auditoría de seguridad del proyecto, ni siquiera es posible hacerlo desde tu máquina — esos puertos ya no se exponen al host, solo son visibles entre contenedores.

## 2. Cómo funciona una transferencia (la parte interesante)

Cuando pides transferir puntos, **no pasa todo de una vez**:

1. Pides transferir → el sistema responde de inmediato "recibido, lo estoy procesando" (`PENDING`, no "listo").
2. Por detrás, `transfer-service` le avisa a `account-service`, vía un sistema de mensajería (Kafka), que debite el saldo de la cuenta origen.
3. `account-service` debita y le avisa de vuelta que salió bien.
4. `transfer-service` entonces le pide que acredite el saldo en la cuenta destino.
5. Si todo salió bien, la transacción queda `COMPLETED`. Si algo falló a mitad de camino, el sistema revierte el débito automáticamente (queda `COMPENSATING` mientras se revierte, y `FAILED` una vez confirmada la reversión).

Esto se llama **saga coreografiada** — es más complejo que un simple "debita y acredita en un solo paso", pero permite que cada servicio sea independiente y escale por su cuenta.

## 3. De dónde sale el saldo de una cuenta (importante — cambió)

**Ya no existe un saldo inicial al crear una cuenta.** `POST /accounts` siempre crea la cuenta con `balance: 0`, sin importar qué mandes en el body. La única forma de que una cuenta tenga puntos es que **alguien se los transfiera**, y ese "alguien" inicial es una cuenta especial llamada `acc-treasury` (la tesorería del sistema), que se crea sola al arrancar `account-service`.

- `acc-treasury` nunca se "recarga" — solo se va poniendo más y más negativa a medida que fondea cuentas. Ese número negativo es, literalmente, el total de puntos que el sistema ha emitido.
- Fondear una cuenta desde `acc-treasury` es una transferencia normal (`POST /api/v1/points/transfer`), pero **solo un usuario con rol `ADMIN`** puede hacerla.
- Cada cuenta tiene además un `minBalance` (piso mínimo, por defecto `0` para cuentas normales, y sin piso para `acc-treasury`) — un `ADMIN` puede ajustarlo con `PATCH /accounts/{id}/limits`.
- Cada movimiento de saldo (débito, crédito, compensación, o el fondeo inicial) queda registrado en un kardex de auditoría consultable en `GET /accounts/{id}/ledger`.

En la sección 5 vas a ver cómo fondear una cuenta de prueba usando el usuario admin ya sembrado en el sistema.

## 4. Qué necesitas instalado antes de empezar

- **Docker Desktop** — corriendo (ícono de Docker activo en la barra de tareas).
- **curl** — ya viene con Git Bash / la mayoría de terminales.
- Nada más. No necesitas Java ni Maven instalados para *probar* el sistema (solo para modificar el código o correr la suite de pruebas automatizadas de la sección 8).

## 5. Levantar el sistema completo

Abre una terminal en la carpeta del proyecto y ejecuta:

```bash
docker-compose up -d --build
```

Esto va a tardar unos minutos la primera vez (descarga imágenes y compila los 4 servicios de negocio). Vas a ver un montón de líneas de texto — es normal.

**Verifica que todo quedó sano:**

```bash
docker-compose ps --format "table {{.Name}}\t{{.Status}}"
```

Debes ver 8 servicios, todos con `(healthy)` al final (los healthchecks corren dentro de la red de Docker, no importa que el puerto no esté expuesto a tu máquina):

```
NAME                                  STATUS
api_fidelizacion-account-service-1    Up ... (healthy)
api_fidelizacion-auth-service-1       Up ... (healthy)
api_fidelizacion-eureka-server-1      Up ... (healthy)
api_fidelizacion-gateway-1            Up ... (healthy)
api_fidelizacion-kafka-1              Up ... (healthy)
api_fidelizacion-keycloak-1           Up ... (healthy)
api_fidelizacion-mongo-1              Up ... (healthy)
api_fidelizacion-transfer-service-1   Up ... (healthy)
```

Si alguno dice `(health: starting)`, espera unos segundos más y vuelve a correr el comando — a veces tardan en encadenarse (Keycloak y Kafka tardan más que el resto).

**Importante si vuelves a construir un solo servicio suelto** (`docker-compose up -d --build account-service`, por ejemplo, después de un cambio de código): puede quedar una entrada vieja en Eureka apuntando al contenedor anterior, y vas a ver errores `500` transitorios en llamadas entre servicios durante uno o dos minutos. Si te pasa, lo más simple y confiable es `docker-compose down -v` seguido de `docker-compose up -d --build` (todo el stack de una vez), no reconstruir servicios sueltos.

## 6. Probar el flujo completo a mano (con curl)

Todo esto se hace contra `http://localhost:8080` (el gateway), nunca contra otro puerto directamente.

### Paso 1 — Registrar un usuario

```bash
curl -X POST http://localhost:8080/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"maria","email":"maria@ejemplo.com","password":"Maria1234!"}'
```

Debes recibir un `201` (sin cuerpo de respuesta, eso es normal).

### Paso 2 — Iniciar sesión y guardar el token

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"maria","password":"Maria1234!"}' \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "Token obtenido: ${TOKEN:0:20}..."
```

Este `TOKEN` es tu "carnet de identidad" — lo vas a mandar en cada request siguiente para probar que eres tú.

### Paso 3 — Crear dos cuentas (origen y destino)

Ya no se manda saldo inicial — toda cuenta nace en 0:

```bash
SRC=$(curl -s -X POST http://localhost:8080/accounts \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{}' | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
echo "Cuenta origen: $SRC (saldo inicial 0)"

TGT=$(curl -s -X POST http://localhost:8080/accounts \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{}' | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
echo "Cuenta destino: $TGT (saldo inicial 0)"
```

### Paso 4 — Fondear la cuenta origen desde la tesorería (requiere admin)

El sistema ya trae sembrado un usuario admin en Keycloak (`test-admin` / `TestAdmin123!`, ver `docker/keycloak/loyalty-realm.json`). Solo un `ADMIN` puede transferir desde `acc-treasury`:

```bash
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test-admin","password":"TestAdmin123!"}' \
  | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

curl -X POST http://localhost:8080/api/v1/points/transfer \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d "{\"sourceAccountId\":\"acc-treasury\",\"targetAccountId\":\"$SRC\",\"amount\":100}"
```

Espera 2-3 segundos y confirma que la cuenta origen ya tiene saldo:

```bash
curl -s http://localhost:8080/accounts/$SRC -H "Authorization: Bearer $TOKEN"
```

Debe mostrar `"balance":100`.

### Paso 5 — Transferir puntos entre las dos cuentas

```bash
curl -X POST http://localhost:8080/api/v1/points/transfer \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":40}"
```

Vas a recibir algo como:
```json
{"transactionId":"...", "status":"PENDING", "createdAt":"..."}
```

`PENDING` significa "en proceso" — es normal, todavía no terminó (ver sección 2).

### Paso 6 — Verificar que se completó

Espera 2-3 segundos y consulta los saldos:

```bash
echo "--- Saldo origen (debe ser 60) ---"
curl -s http://localhost:8080/accounts/$SRC -H "Authorization: Bearer $TOKEN"
echo ""
echo "--- Saldo destino (debe ser 40) ---"
curl -s http://localhost:8080/accounts/$TGT -H "Authorization: Bearer $TOKEN"
```

Si ves `"balance":60` en el origen y `"balance":40` en el destino: **funcionó de punta a punta.**

### Paso 7 (opcional) — Ver el historial de transacciones de una cuenta

```bash
curl -s http://localhost:8080/accounts/$SRC/transactions -H "Authorization: Bearer $TOKEN"
```

Debe mostrar las transacciones (fondeo y transferencia) con `"status":"COMPLETED"`.

### Paso 8 (opcional) — Ver el kardex de auditoría de una cuenta

```bash
curl -s http://localhost:8080/accounts/$SRC/ledger -H "Authorization: Bearer $TOKEN"
```

Debe mostrar cada movimiento de saldo con `balanceBefore`/`balanceAfter` y su tipo (`SEED` para el fondeo inicial desde tesorería, `DEBIT`/`CREDIT` para transferencias normales).

## 7. Casos que deben fallar (para entender las reglas)

**Transferir más de lo que tienes:**
```bash
curl -X POST http://localhost:8080/api/v1/points/transfer \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"sourceAccountId\":\"$SRC\",\"targetAccountId\":\"$TGT\",\"amount\":999999}"
```
Debe responder `422` con `"code":"INSUFFICIENT_BALANCE"`.

**Transferir desde una cuenta que no es tuya** (crea un segundo usuario y prueba a transferir usando el ID de la cuenta de `maria` con el token del otro usuario): debe responder `403 Forbidden`.

**Crear una cuenta sin estar logueado:** debe responder `401`.

**Un usuario normal intentando fondear desde `acc-treasury`** (usar `$TOKEN` de `maria` en vez de `$ADMIN_TOKEN` en el paso 4): debe responder `403 Forbidden` (solo `ADMIN` puede tocar la tesorería).

**Un usuario normal intentando bloquear una cuenta o ajustar límites** (`PATCH /accounts/{id}/status` o `PATCH /accounts/{id}/limits` con `$TOKEN` en vez de `$ADMIN_TOKEN`): debe responder `403 Forbidden`.

Para la lista completa de casos positivos y negativos por endpoint (más de 60), ver `docs/api-tests.http` (sección 9) o la suite automatizada (sección 8).

## 8. Ejecutar las pruebas automatizadas (para verificar que nada se rompió)

Con el sistema arriba (sección 5), desde la raíz del proyecto:

```bash
mvn -pl e2e-tests test -Dmaven.test.skip=false
```

Corre **65 escenarios reales** contra el sistema completo: los 6 de la saga multi-servicio (flujo feliz, saldo insuficiente, autorización, idempotencia, resiliencia, compensación) y 59 más que cubren cada endpoint de `auth-service`, `account-service` y `transfer-service` en sus casos positivos y negativos (validación, roles, ownership, 404). Debe terminar en `BUILD SUCCESS` con `Tests run: 65, Failures: 0`.

Para correr solo las pruebas unitarias/de integración de cada servicio (sin necesitar Docker arriba):

```bash
mvn clean install
```

## 9. Probar a mano sin escribir curl (archivo REST Client)

`docs/api-tests.http` trae los mismos escenarios de la sección 8 listos para ejecutar uno por uno desde el editor, sin necesidad de Maven ni de armar los comandos `curl` a mano — solo con la extensión **REST Client** de VS Code (o el cliente HTTP equivalente de tu IDE). Abre ese archivo, corre los bloques de arriba hacia abajo (algunos capturan automáticamente el token/id del bloque anterior), y cada uno indica si es un caso `[POSITIVO]` o `[NEGATIVO]` y qué respuesta esperar.

## 10. Apagar todo

```bash
docker-compose down -v
```

Esto borra los contenedores y sus datos (usuarios, cuentas, transacciones creadas en las pruebas) — la próxima vez que hagas `docker-compose up` empiezas de cero (incluyendo que `acc-treasury` se vuelve a sembrar en 0).

## 11. Problemas comunes

| Síntoma | Causa probable | Solución |
|---|---|---|
| `503 Service Unavailable` justo después de levantar el stack | Los servicios recién se registraron en Eureka, aún no propaga | Espera 15-20 segundos y reintenta |
| `401` en login recién registrado | Nombre de usuario repetido de una corrida anterior | Usa un `username` distinto, o `docker-compose down -v` para empezar limpio |
| `500` intermitente en una transferencia justo después de reconstruir un solo servicio | Entrada vieja en Eureka apuntando al contenedor anterior (ver nota de la sección 5) | `docker-compose down -v && docker-compose up -d --build` (el stack completo) |
| `403` al intentar fondear desde `acc-treasury` o bloquear/ajustar límites de una cuenta | Estás usando un token de usuario normal, no de admin | Loguéate con `test-admin`/`TestAdmin123!` (paso 4 de la sección 6) |
| Un contenedor queda en `(unhealthy)` | Algo no arrancó bien | `docker logs <nombre-del-contenedor>` para ver el error |
| Docker Desktop no responde | No está corriendo | Ábrelo manualmente y espera a que el ícono deje de "cargar" |

## 12. Dónde está cada cosa en el código (por si quieres mirar)

- `docs/FUNCIONAL.md` — qué hace el sistema, reglas de negocio.
- `docs/ARQUITECTURA.md` — cómo está armado por dentro, decisiones técnicas.
- `docs/superpowers/specs/` — la especificación detallada de cada fase y feature (incluye `balance_ledger`, la tesorería y `minBalance`).
- `docs/superpowers/plans/` — el plan de implementación de cada fase, incluyendo los bugs reales que se encontraron y cómo se corrigieron, y el plan de la auditoría de seguridad más reciente.
- `docs/api-tests.http` — todos los escenarios positivos/negativos por endpoint, listos para ejecutar (sección 9).
- `account-service/`, `transfer-service/`, `auth-service/`, `gateway/`, `eureka-server/` — el código de cada microservicio.
- `e2e-tests/` — las 65 pruebas que ejercitan el sistema completo (sección 8 de esta guía).
