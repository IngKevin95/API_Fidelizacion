# Plan de implementación: correcciones críticas de la auditoría 2026-09-15

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corregir los 4 hallazgos críticos/altos de la auditoría de seguridad+arquitectura del 2026-09-15 (2 agentes Claude + 2 sesiones OpenCode en paralelo, con verificación manual de cada hallazgo): (1) el flujo de fondeo desde `acc-treasury` está roto porque `transfer-service` ignora `minBalance`, (2) la suite E2E quedó rota por el cambio de contrato de `POST /accounts` (Task 1 de la feature `balance_ledger`), (3) `GET /transactions` en `transfer-service` no valida ownership (IDOR), (4) el `CorsConfig` del gateway combina origen comodín con credenciales habilitadas.

**Architecture:** No se crea ningún servicio nuevo. Cambios acotados a 3 módulos existentes: `transfer-service` (2 fixes independientes entre sí), `gateway` (1 fix de una sola clase), `e2e-tests` (reescritura de 2 escenarios, depende de que el fix de `transfer-service` #1 ya esté en `develop`).

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Cloud Gateway (reactivo), MongoDB, Kafka, RestAssured/Awaitility (e2e).

**Spec:** Este plan no tiene un documento de spec separado — el hallazgo de auditoría (transcripto en la conversación del 2026-09-15, sesión de auditoría paralela Claude+OpenCode) hace las veces de spec. Cada tarea cita el hallazgo exacto que resuelve.

## Global Constraints

- Ningún fix de este plan modifica el contrato público de un endpoint salvo lo estrictamente necesario para cerrar el hallazgo (no se agregan campos/endpoints nuevos fuera de alcance).
- `minBalance` se trata siempre como "sin piso" cuando es `null` (mismo criterio ya usado en `account-service`, D2 de la spec `balance-ledger-design.md`).
- Ninguna tarea de este plan toca `account-service` — los 4 hallazgos están en `transfer-service`, `gateway` y `e2e-tests`.
- GitFlow normal: una branch por tarea, PR individual, aprobación explícita antes de cada merge — la única diferencia respecto al patrón anterior es que las Tasks 1-3 se **ejecutan en paralelo** en worktrees separados (ver sección de delegación), no que se mergeen sin aprobación.

---

## Estrategia de delegación y ejecución en paralelo (worktrees)

Las Tasks 1, 2 y 3 tocan archivos completamente distintos (ninguna comparte un archivo con otra) y no tienen dependencias entre sí, así que se ejecutan **simultáneamente en 3 git worktrees separados**, cada uno delegado a una sesión OpenCode independiente. La Task 4 depende de que la Task 1 ya esté mergeada a `develop` (necesita el fix real para poder fondear cuentas desde `acc-treasury` en el escenario E2E), así que arranca después, en un worktree nuevo creado sobre el `develop` ya actualizado.

| Tarea | Módulo | Archivos que toca | Modelo OpenCode | Por qué ese modelo |
|---|---|---|---|---|
| Task 1 | transfer-service | `AccountView.java`, `TransferServiceImpl.java`, `TransferServiceTest.java` | `google/antigravity-gemini-3.1-pro` | Lógica financiera (condición de débito), sensible a errores sutiles de signo/comparación — amerita el modelo más capaz. |
| Task 2 | transfer-service | `TransactionQueryController.java`, `TransactionQueryControllerIT.java` | `google/antigravity-gemini-3.1-pro` | Fix de seguridad (IDOR) — igual criterio: nunca delegar seguridad al modelo más barato. |
| Task 3 | gateway | `CorsConfig.java`, `CorsConfigTest.java` | `google/antigravity-gemini-3-flash` | Cambio mecánico de una sola clase (ajustar 2 propiedades + hacerlas configurables), sin lógica de negocio — boilerplate de bajo riesgo. |
| Task 4 | e2e-tests | `HappyPathE2ETest.java`, `CompensationE2ETest.java`, `support/` | `google/antigravity-gemini-3.1-pro` | Reescribe flujo de saga completo (login admin, transfer desde tesorería, polling) — lógica de test no trivial, requiere el modelo más capaz. Arranca solo después de que Task 1 esté en `develop`. |

**Setup de worktrees (Tasks 1-3, en paralelo):**

```bash
cd E:\Datos\Documentos\GitHub\API_Fidelizacion
git worktree add ../audit-fix-task1 -b feature/audit-fix-treasury-minbalance develop
git worktree add ../audit-fix-task2 -b feature/audit-fix-transactions-idor develop
git worktree add ../audit-fix-task3 -b feature/audit-fix-cors develop
```

**Lanzamiento de las 3 sesiones OpenCode (en background, cada una en su propio worktree/`--dir`):**

```bash
opencode run "<prompt Task 1, ver abajo>" --format json --title "audit-fix-treasury-minbalance" --dir "E:\Datos\Documentos\GitHub\API_Fidelizacion\..\audit-fix-task1" --agent build --model google/antigravity-gemini-3.1-pro > audit-fix-task1.jsonl 2>&1 &

opencode run "<prompt Task 2, ver abajo>" --format json --title "audit-fix-transactions-idor" --dir "E:\Datos\Documentos\GitHub\API_Fidelizacion\..\audit-fix-task2" --agent build --model google/antigravity-gemini-3.1-pro > audit-fix-task2.jsonl 2>&1 &

opencode run "<prompt Task 3, ver abajo>" --format json --title "audit-fix-cors" --dir "E:\Datos\Documentos\GitHub\API_Fidelizacion\..\audit-fix-task3" --agent build --model google/antigravity-gemini-3-flash > audit-fix-task3.jsonl 2>&1 &
```

Cada sesion corre `mvn -pl <modulo> -am test` dentro de su propio worktree antes de reportar terminado (working directories aislados = sin pisarse los `target/` entre sí). Al terminar cada una, se extrae el `sessionID` de la primera línea del `.jsonl`, se **audita el diff manualmente** (yo, no el reporte del agente) corriendo los tests reales, y solo entonces se commitea/pushea/PR desde ese worktree.

**Orden de merge (secuencial, GitFlow normal, un PR aprobado a la vez):** Task 1 → Task 2 → Task 3, en cualquier orden entre sí realmente (no tienen dependencia), pero deben mergearse una por una con aprobación explícita antes de la siguiente, igual que siempre. **Task 4 no arranca hasta que Task 1 esté mergeada a `develop`** (se crea su worktree después, sobre `develop` actualizado).

**Limpieza al terminar:**

```bash
git worktree remove ../audit-fix-task1
git worktree remove ../audit-fix-task2
git worktree remove ../audit-fix-task3
```

---

## File Structure

```
transfer-service/src/main/java/com/loyalty/transfer/
├── dto/AccountView.java                    # MODIFICAR (Task 1): agregar minBalance
├── service/TransferServiceImpl.java        # MODIFICAR (Task 1): validacion con minBalance
└── controller/TransactionQueryController.java  # MODIFICAR (Task 2): ownership check

gateway/src/main/java/com/loyalty/gateway/config/
└── CorsConfig.java                         # MODIFICAR (Task 3): origenes configurables, sin credentials

e2e-tests/src/test/java/com/loyalty/e2e/
├── HappyPathE2ETest.java                   # MODIFICAR (Task 4): fondeo real desde acc-treasury
├── CompensationE2ETest.java                # MODIFICAR (Task 4): idem
└── support/AdminTransferFixture.java       # NUEVO (Task 4): helper de fondeo admin
```

---

### Task 1: `transfer-service` respeta `minBalance` al validar saldo (hallazgo crítico #1)

**Hallazgo que resuelve:** `TransferServiceImpl.java:47` usa `source.getBalance() < amount`, ignorando `minBalance` por completo. `AccountView` no mapea ese campo. Cualquier transferencia desde `acc-treasury` (el único mecanismo de fondeo inicial, D3 de `balance-ledger-design.md`) es rechazada con `INSUFFICIENT_BALANCE` antes de llegar a la validación atómica real de `account-service`.

**Files:**
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/dto/AccountView.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/service/TransferServiceImpl.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/service/TransferServiceTest.java`

**Interfaces:**
- Consumes: `AccountClient.fetchAccount(String, String)` (ya existente, retorna `AccountView` — el JSON de `account-service` ya incluye `minBalance` desde la feature `balance_ledger`, Jackson lo deserializa automáticamente en cuanto el campo exista en el DTO).
- Produces: `AccountView.getMinBalance()`/`setMinBalance(Long)`. No cambia ninguna firma pública consumida por otras tareas de este plan.

- [ ] **Step 1: Escribir el test que falla — transferencia desde una cuenta con `minBalance` negativo debe permitirse aunque el saldo bruto sea menor al monto**

Agregar a `transfer-service/src/test/java/com/loyalty/transfer/service/TransferServiceTest.java`:
```java
    @Test
    void allowsTransferBelowZeroWhenMinBalanceIsNegative() {
        AccountView source = accountView("acc-treasury", "system", 0L, "ACTIVE");
        source.setMinBalance(-1_000_000L);
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-treasury", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = transferService.initiate("acc-treasury", "acc-2", 500L, "admin-1", "token");

        assertThat(result.getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void rejectsWhenResultWouldGoBelowMinBalance() {
        AccountView source = accountView("acc-1", "user-1", 10L, "ACTIVE");
        source.setMinBalance(0L);
        AccountView target = accountView("acc-2", "user-2", 0L, "ACTIVE");
        when(accountClient.fetchAccount("acc-1", "token")).thenReturn(source);
        when(accountClient.fetchAccount("acc-2", "token")).thenReturn(target);

        assertThatThrownBy(() -> transferService.initiate("acc-1", "acc-2", 40L, "user-1", "token"))
                .isInstanceOf(TransferUnprocessableException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_BALANCE");
    }
```
Y actualizar el helper `accountView` para que reciba `minBalance` opcionalmente (sobrecarga, sin romper las llamadas existentes):
```java
    private AccountView accountView(String id, String ownerId, long balance, String status) {
        AccountView view = new AccountView();
        view.setId(id);
        view.setOwnerId(ownerId);
        view.setBalance(balance);
        view.setStatus(status);
        return view;
    }
```
(no hace falta tocar esta firma — `setMinBalance` se llama aparte en los tests nuevos, como se ve arriba).

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -pl transfer-service -am test -Dtest=TransferServiceTest`
Expected: FAIL — `setMinBalance` no existe en `AccountView`, y `rejectsWhenResultWouldGoBelowMinBalance` compila pero pasaría por casualidad con la lógica vieja (el primer test es el que realmente prueba el fix).

- [ ] **Step 3: Agregar `minBalance` a `AccountView`**

```java
    private Long minBalance;

    public Long getMinBalance() {
        return minBalance;
    }

    public void setMinBalance(Long minBalance) {
        this.minBalance = minBalance;
    }
```

- [ ] **Step 4: Corregir la validación en `TransferServiceImpl`**

Reemplazar:
```java
        if (source.getBalance() < amount) {
            throw new TransferUnprocessableException("INSUFFICIENT_BALANCE", "Saldo insuficiente en la cuenta origen");
        }
```
por:
```java
        long effectiveMinBalance = source.getMinBalance() != null ? source.getMinBalance() : 0L;
        if (source.getBalance() - amount < effectiveMinBalance) {
            throw new TransferUnprocessableException("INSUFFICIENT_BALANCE", "Saldo insuficiente en la cuenta origen");
        }
```

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `mvn -pl transfer-service -am test -Dtest=TransferServiceTest`
Expected: PASS (7 tests: los 5 existentes + los 2 nuevos).

- [ ] **Step 6: Ejecutar toda la suite de `transfer-service` para confirmar que no rompió nada**

Run: `mvn -pl transfer-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/dto/AccountView.java transfer-service/src/main/java/com/loyalty/transfer/service/TransferServiceImpl.java transfer-service/src/test/java/com/loyalty/transfer/service/TransferServiceTest.java
git commit -m "fix: transfer-service respeta minBalance al validar saldo origen"
```

---

### Task 2: `GET /transactions` valida ownership antes de listar (hallazgo crítico #3 — IDOR)

**Hallazgo que resuelve:** `TransactionQueryController.java` no tiene `@PreAuthorize` ni chequeo de ownership — cualquier usuario autenticado puede pasar cualquier `accountId` ajeno y ver su historial completo de transacciones.

**Files:**
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/controller/TransactionQueryController.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/controller/TransactionQueryControllerIT.java`

**Interfaces:**
- Consumes: `AccountClient.fetchAccount(String accountId, String bearerToken)` (ya existente — `account-service` ya enforce ownership/rol ADMIN en `GET /accounts/{id}` y lanza `403`, que `AccountClient` traduce a `TransferAccessDeniedException`). Reutilizar esa validación evita duplicar la lógica de ownership en dos servicios.
- Produces: ningún cambio de firma pública nueva — el endpoint sigue siendo `GET /transactions?accountId=`, ahora exige que el JWT forwarded sea dueño de la cuenta o ADMIN.

- [ ] **Step 1: Escribir el test que falla — un usuario no dueño recibe 403**

Agregar a `TransactionQueryControllerIT.java` (agregar `MockBean` de `AccountClient` ya que el test es `@SpringBootTest` con Testcontainers Mongo, sin levantar `account-service` real):
```java
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.loyalty.transfer.client.AccountClient accountClient;

    @Test
    void rejectsWhenRequesterIsNotOwnerNorAdmin() throws Exception {
        saveTransaction("tx-1", "acc-1", "acc-2");
        org.mockito.Mockito.when(accountClient.fetchAccount(org.mockito.ArgumentMatchers.eq("acc-1"), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new com.loyalty.transfer.exception.TransferAccessDeniedException("No autorizado para acceder a la cuenta acc-1"));

        mockMvc.perform(get("/transactions")
                        .param("accountId", "acc-1")
                        .with(jwt().jwt(j -> j.subject("intruder")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }
```
Y actualizar el test existente `listsTransactionsWhereAccountIsSourceOrTarget` para stubear el `AccountClient` como si el requester fuera dueño:
```java
    @Test
    void listsTransactionsWhereAccountIsSourceOrTarget() throws Exception {
        saveTransaction("tx-1", "acc-1", "acc-2");
        saveTransaction("tx-2", "acc-3", "acc-1");
        saveTransaction("tx-3", "acc-4", "acc-5");
        org.mockito.Mockito.when(accountClient.fetchAccount(org.mockito.ArgumentMatchers.eq("acc-1"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new com.loyalty.transfer.dto.AccountView());

        mockMvc.perform(get("/transactions")
                        .param("accountId", "acc-1")
                        .with(jwt().jwt(j -> j.subject("user-1")).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -pl transfer-service -am test -Dtest=TransactionQueryControllerIT`
Expected: FAIL — `rejectsWhenRequesterIsNotOwnerNorAdmin` recibe `200` en vez de `403` (el controller no llama a `accountClient` todavia).

- [ ] **Step 3: Agregar el chequeo de ownership al controller**

```java
package com.loyalty.transfer.controller;

import com.loyalty.transfer.client.AccountClient;
import com.loyalty.transfer.dto.TransactionResponse;
import com.loyalty.transfer.repository.TransactionRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class TransactionQueryController {

    private final TransactionRepository transactionRepository;
    private final AccountClient accountClient;

    public TransactionQueryController(TransactionRepository transactionRepository, AccountClient accountClient) {
        this.transactionRepository = transactionRepository;
        this.accountClient = accountClient;
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> listByAccount(@RequestParam("accountId") String accountId,
                                                     Authentication authentication) {
        String bearerToken = ((JwtAuthenticationToken) authentication).getToken().getTokenValue();
        accountClient.fetchAccount(accountId, bearerToken);

        return transactionRepository.findBySourceAccountIdOrTargetAccountId(accountId, accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
```
(`accountClient.fetchAccount` lanza `TransferAccessDeniedException`/`AccountNotFoundException` si el requester no es dueño ni ADMIN, o si la cuenta no existe — ambas ya mapeadas por el `GlobalExceptionHandler` existente de `transfer-service` a `403`/`404` respectivamente, sin cambios adicionales necesarios ahi).

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -pl transfer-service -am test -Dtest=TransactionQueryControllerIT`
Expected: PASS (2 tests).

- [ ] **Step 5: Ejecutar toda la suite de `transfer-service`**

Run: `mvn -pl transfer-service -am test`
Expected: BUILD SUCCESS. Revisar en particular que `TransferClient` (el llamador real desde `account-service`) siga funcionando — no se toca su contrato, solo se agrega una validación adicional del lado servidor.

- [ ] **Step 6: Commit**

```bash
git add transfer-service/src/main/java/com/loyalty/transfer/controller/TransactionQueryController.java transfer-service/src/test/java/com/loyalty/transfer/controller/TransactionQueryControllerIT.java
git commit -m "fix: GET /transactions valida ownership via AccountClient (cierra IDOR)"
```

---

### Task 3: CORS del gateway sin comodín + credenciales (hallazgo crítico #4)

**Hallazgo que resuelve:** `CorsConfig.java` combina `setAllowedOriginPatterns(List.of("*"))` con `setAllowCredentials(true)`, permitiendo que cualquier origen reciba respuestas con credenciales del navegador. Esta API es 100% Bearer-JWT (sin cookies de sesión, ver `csrf().disable()` en cada servicio de negocio), por lo que `allowCredentials` no aporta nada funcional hoy — desactivarlo es la corrección correcta, no una regresión.

**Files:**
- Modify: `gateway/src/main/java/com/loyalty/gateway/config/CorsConfig.java`
- Test: `gateway/src/test/java/com/loyalty/gateway/config/CorsConfigTest.java`
- Modify: `gateway/src/main/resources/application.yml` (nueva propiedad `cors.allowed-origins`)

**Interfaces:**
- Consumes: nada nuevo.
- Produces: `CorsConfig` pasa a leer `cors.allowed-origins` (lista separada por coma, default `*` para desarrollo local) via `@Value`, y `allowCredentials` queda en `false`. No consumido por ninguna otra tarea de este plan.

- [ ] **Step 1: Escribir el test que falla**

Reemplazar `gateway/src/test/java/com/loyalty/gateway/config/CorsConfigTest.java`:
```java
package com.loyalty.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void createsCorsWebFilterAllowingConfiguredOriginsAndCommonMethods() {
        CorsConfig config = new CorsConfig("http://localhost:3000,https://app.example.com");

        CorsWebFilter filter = config.corsWebFilter();

        assertThat(filter).isNotNull();
    }

    @Test
    void configurationAllowsExpectedMethodsAndConfiguredOriginsWithoutCredentials() {
        CorsConfig config = new CorsConfig("http://localhost:3000,https://app.example.com");
        CorsConfiguration corsConfiguration = config.buildCorsConfiguration();

        assertThat(corsConfiguration.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");
        assertThat(corsConfiguration.getAllowedOriginPatterns())
                .containsExactlyInAnyOrder("http://localhost:3000", "https://app.example.com");
        assertThat(corsConfiguration.getAllowCredentials()).isFalse();
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -pl gateway -am test -Dtest=CorsConfigTest`
Expected: FAIL — `CorsConfig` no tiene un constructor con `String`.

- [ ] **Step 3: Reescribir `CorsConfig`**

```java
package com.loyalty.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins:*}") String allowedOrigins) {
        this.allowedOrigins = Arrays.asList(allowedOrigins.split(","));
    }

    public CorsConfiguration buildCorsConfiguration() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);
        return configuration;
    }

    @Bean
    public CorsWebFilter corsWebFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildCorsConfiguration());
        return new CorsWebFilter(source);
    }
}
```

- [ ] **Step 4: Agregar la propiedad a `application.yml`**

En `gateway/src/main/resources/application.yml`, agregar (nivel raíz):
```yaml
cors:
  allowed-origins: "*"
```
(el default de desarrollo se mantiene permisivo en origen, pero ya sin `allowCredentials` — quien despliegue a un entorno real sobreescribe `cors.allowed-origins` con la whitelist real vía variable de entorno `CORS_ALLOWED_ORIGINS`).

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `mvn -pl gateway -am test -Dtest=CorsConfigTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Ejecutar toda la suite de `gateway`**

Run: `mvn -pl gateway -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add gateway/src/main/java/com/loyalty/gateway/config/CorsConfig.java gateway/src/test/java/com/loyalty/gateway/config/CorsConfigTest.java gateway/src/main/resources/application.yml
git commit -m "fix: CORS del gateway sin credenciales y con origenes configurables"
```

---

### Task 4: Reescribir E2E para fondear cuentas vía transferencia real desde `acc-treasury` (hallazgo crítico #2)

**Depende de:** Task 1 ya mergeada a `develop` (necesita que `transfer-service` respete `minBalance` para que una transferencia desde `acc-treasury` no sea rechazada).

**Hallazgo que resuelve:** `HappyPathE2ETest`/`CompensationE2ETest` envían `{"balance": 100}` en `POST /accounts`, un campo que el DTO ignora desde la Task 1 de la feature `balance_ledger` (D3: toda cuenta nace en 0). El escenario feliz completo del E2E queda inválido/roto sin que nada lo señale.

**Files:**
- Create: `e2e-tests/src/test/java/com/loyalty/e2e/support/AdminTransferFixture.java`
- Modify: `e2e-tests/src/test/java/com/loyalty/e2e/HappyPathE2ETest.java`
- Modify: `e2e-tests/src/test/java/com/loyalty/e2e/CompensationE2ETest.java`

**Interfaces:**
- Consumes: `AuthTestFixture` (ya existente, registro/login contra Keycloak real vía gateway), `ApiClient` (ya existente, wrapper HTTP contra el gateway).
- Produces: `AdminTransferFixture.fundAccount(String targetAccountId, long amount)` — hace login como `test-admin` (usuario ya sembrado en el realm de Keycloak, ver `docker/keycloak/loyalty-realm.json`) y ejecuta `POST /api/v1/points/transfer` desde `acc-treasury` hacia `targetAccountId`, esperando (polling con Awaitility, mismo patrón que el resto de la suite) a que la transacción quede `COMPLETED` antes de devolver el control. Consumida por `HappyPathE2ETest` y `CompensationE2ETest`.

- [ ] **Step 1: Leer el fixture y test existentes para replicar sus convenciones exactas**

Antes de escribir código, leer `e2e-tests/src/test/java/com/loyalty/e2e/support/AuthTestFixture.java`, `e2e-tests/src/test/java/com/loyalty/e2e/support/ApiClient.java` y `e2e-tests/src/test/java/com/loyalty/e2e/HappyPathE2ETest.java` completos para copiar el estilo real de polling/RestAssured usado (no asumir la firma — el ejecutor de esta tarea debe confirmar los métodos exactos disponibles en `ApiClient` antes de escribir `AdminTransferFixture`, ya que este plan no transcribe su contenido completo por no haber sido re-leído en esta sesión de auditoría).

- [ ] **Step 2: Crear `AdminTransferFixture` siguiendo exactamente esas convenciones**

Implementar `fundAccount(String targetAccountId, long amount)`:
1. Login como `test-admin`/`TestAdmin123!` (mismo patrón que cualquier otro login admin ya usado en la suite, si existe — revisar `OwnershipE2ETest`/`ResilienceE2ETest` por si ya hay un helper de admin reutilizable antes de duplicar).
2. `POST /api/v1/points/transfer` con `sourceAccountId="acc-treasury"`, `targetAccountId`, `amount`.
3. Poll (Awaitility, timeout consistente con el resto de la suite — revisar el valor exacto usado en `HappyPathE2ETest` actual) hasta que `GET /accounts/{id}/transactions` o el mismo `transactionId` reporte `COMPLETED`.

- [ ] **Step 3: Actualizar `HappyPathE2ETest`**

Reemplazar la creación de la cuenta origen (que hoy manda `{"balance": 100}`) por:
1. `POST /accounts` con body vacío (`{}`) para la cuenta origen.
2. `AdminTransferFixture.fundAccount(sourceAccountId, 100)` para llevarla a saldo 100 vía transferencia real desde `acc-treasury`.
3. El resto del test (transferir 40 de origen a destino, verificar saldos 60/40) queda igual.

- [ ] **Step 4: Actualizar `CompensationE2ETest`** con el mismo patrón de fondeo.

- [ ] **Step 5: Ejecutar la suite E2E completa contra el stack real**

Requiere `docker-compose up -d --build` con los fixes de las Tasks 1-3 ya en la imagen (o al menos Task 1, que es la dependencia funcional real).

Run: `mvn -pl e2e-tests test -Dmaven.test.skip=false`
Expected: `Tests run: 6, Failures: 0` — BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add e2e-tests/src/test/java/com/loyalty/e2e/support/AdminTransferFixture.java e2e-tests/src/test/java/com/loyalty/e2e/HappyPathE2ETest.java e2e-tests/src/test/java/com/loyalty/e2e/CompensationE2ETest.java
git commit -m "fix: E2E fondea cuentas via transferencia real desde acc-treasury (contrato POST /accounts sin balance)"
```

---

## Self-Review

**1. Cobertura de hallazgos:** Task 1 → hallazgo crítico #1 (minBalance ignorado en transfer-service). Task 2 → hallazgo crítico #3 (IDOR en /transactions). Task 3 → hallazgo crítico #4 (CORS). Task 4 → hallazgo crítico #2 (E2E roto), y depende explícitamente de Task 1 por ser la misma brecha vista desde el otro lado. Los hallazgos de severidad media/baja (SEED mal inferido, CompensationApplied ausente, Resilience4j faltante, secretos hardcodeados, puertos expuestos, `@Valid` faltante en `UpdateLimitsRequest`) quedan fuera de este plan — son backlog técnico, no los 4 puntos que el usuario pidió atacar primero.

**2. Placeholders:** Task 4 Step 1 y Step 2 son deliberadamente menos prescriptivos que el resto (piden leer los fixtures reales antes de escribir código) porque este plan no releyó el contenido completo de `AuthTestFixture`/`ApiClient`/`HappyPathE2ETest` en esta sesión — es la única excepción al principio de "no placeholders", justificada explícitamente en vez de inventar una firma que podría no coincidir con el fixture real y romper la compilación. El ejecutor de esa tarea debe leer esos 3 archivos como primer paso obligatorio antes de escribir cualquier código.

**3. Consistencia de tipos:** `AccountView.getMinBalance()`/`setMinBalance(Long)` (Task 1) es el único símbolo nuevo compartido entre tareas de este plan, y no lo consume ninguna otra tarea (Task 2 no toca `AccountView`). Sin conflictos de nombres entre tareas.

## Ejecución

Una vez aprobado este plan, se ejecuta con `superpowers:subagent-driven-development`: Tasks 1-3 se lanzan en paralelo (3 worktrees, 3 sesiones OpenCode simultáneas como se describe arriba), yo audito cada diff y corro los tests reales en su propio worktree antes de commitear, y recién cuando las 3 tengan PR abierto se piden las aprobaciones de merge (una por una, orden indistinto entre ellas). Task 4 se planifica igual pero no se lanza hasta que Task 1 esté mergeada a `develop`.
