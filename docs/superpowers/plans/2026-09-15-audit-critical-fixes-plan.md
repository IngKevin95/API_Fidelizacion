# Plan de implementación: correcciones de la auditoría 2026-09-15 (completo)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corregir **todos** los hallazgos de la auditoría de seguridad+arquitectura del 2026-09-15 (2 agentes Claude + 2 sesiones OpenCode en paralelo, con verificación manual de cada hallazgo), agrupados en 4 críticos (Tasks 1-4), 4 altos (Tasks 5-8) y 5 medios/bajos (Tasks 9-13). Un hallazgo reportado por una de las 4 auditorías (`@Autowired` como "inyección por campo" en `TransferClient`/`AccountClient`) se verificó como **inexacto** — ya usa inyección por constructor (patrón de doble constructor para testabilidad) — y se excluye de este plan; ver nota en Self-Review.

**Architecture:** No se crea ningún servicio nuevo. Cambios acotados a los 5 módulos existentes que corresponda según el hallazgo: `transfer-service`, `account-service`, `gateway`, `e2e-tests`, e infraestructura (`docker-compose.yml`, `docker/kafka/`, `docker/keycloak/`). 13 tareas en total, organizadas en 3 oleadas de ejecución paralela según dependencias reales de archivo (ver sección de delegación).

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Cloud Gateway (reactivo), Spring Kafka, Resilience4j, MongoDB, Kafka (KRaft, SASL/SCRAM), RestAssured/Awaitility (e2e).

**Spec:** Este plan no tiene un documento de spec separado — el hallazgo de auditoría (transcripto en la conversación del 2026-09-15, sesión de auditoría paralela Claude+OpenCode, 4 agentes independientes) hace las veces de spec. Cada tarea cita el hallazgo exacto que resuelve.

## Global Constraints

- Ningún fix de este plan modifica el contrato público de un endpoint salvo lo estrictamente necesario para cerrar el hallazgo (no se agregan campos/endpoints nuevos fuera de alcance).
- `minBalance` se trata siempre como "sin piso" cuando es `null` (mismo criterio ya usado en `account-service`, D2 de la spec `balance-ledger-design.md`).
- Ningún cambio de este plan modifica el `saldo` real ni la lógica de negocio central de `AccountAtomicOperationsImpl` fuera de lo que cada tarea señala explícitamente (Tasks 5 y 12 son las únicas que tocan esa clase, y no se solapan en las líneas que modifican).
- GitFlow normal: una branch por tarea, PR individual, aprobación explícita antes de cada merge — el paralelismo es en la **ejecución/desarrollo** (worktrees + oleadas), nunca en el orden ni la aprobación del merge.

---

## Estrategia de delegación y ejecución en paralelo (worktrees)

13 tareas en total. Se agrupan en **3 oleadas** según sus dependencias reales (una tarea solo depende de otra si toca el mismo archivo/contrato que la otra modifica); dentro de cada oleada, todas las tareas son independientes entre sí y corren **en paralelo, cada una en su propio worktree**.

| Tarea | Severidad | Módulo(s) | Archivos que toca | Modelo OpenCode | Por qué ese modelo |
|---|---|---|---|---|---|
| Task 1 | Crítico | transfer-service | `AccountView.java`, `TransferServiceImpl.java`, `TransferServiceTest.java` | `antigravity-gemini-3.1-pro` | Lógica financiera (condición de débito). |
| Task 2 | Crítico | transfer-service | `TransactionQueryController.java`, `TransactionQueryControllerIT.java` | `antigravity-gemini-3.1-pro` | Fix de seguridad (IDOR). |
| Task 3 | Crítico | gateway | `CorsConfig.java`, `CorsConfigTest.java`, `application.yml` | `antigravity-gemini-3-flash` | Cambio mecánico de una sola clase. |
| Task 5 | Alto | account-service, transfer-service | `CreditRequestedEvent.java` (ambos módulos), `TransferSagaPublisher.java`, `TransferSagaListener.java`, `SagaEventListener.java` (account-service), tests IT | `antigravity-gemini-3.1-pro` | Cambia la semántica de un evento de dominio que cruza 2 servicios — riesgo de romper la saga si se hace mal. |
| Task 6 | Alto | account-service, transfer-service | `CompensationResultEvent.java` (nuevo, ambos), `SagaEventPublisher.java`, `SagaEventListener.java`, `TransferSagaListener.java`, `TransactionStatus.java`, topics script, tests IT | `antigravity-gemini-3.1-pro` | Agrega un evento nuevo a la saga coreografiada — el de mayor riesgo de este plan, requiere entender el flujo completo. |
| Task 7 | Alto | account-service, transfer-service | `pom.xml` (ambos), `TransferClient.java`, `AccountClient.java`, `application.yml` (ambos) | `antigravity-gemini-3.1-pro` | Nueva dependencia (Resilience4j) + timeouts — errores de configuración rompen silenciosamente la comunicación inter-servicio. |
| Task 8 | Alto+Medio | infra (docker-compose, kafka) | `docker-compose.yml`, `docker/kafka/create-topics.sh`, `docker/kafka/create-acls.sh` (nuevo), `application.yml` (account-service, transfer-service) | `antigravity-gemini-3.1-pro` | Cambio de infraestructura con mayor superficie de "romperlo todo" (Kafka/Mongo dejan de ser alcanzables si se configura mal) — requiere validar contra el stack real levantado. |
| Task 9 | Medio | account-service | `UpdateLimitsRequest.java`, `AccountController.java`, test IT | `antigravity-gemini-3-flash` | Una anotación de validación + `@Valid` — mecánico. |
| Task 10 | Medio | auth-service, docker-compose, keycloak | `application.yml` (auth-service), `docker-compose.yml`, `docker/keycloak/loyalty-realm.json` | `antigravity-gemini-3-flash` | Sustituir literales por `${VAR:default}` — mecánico, sin lógica. |
| Task 11 | Medio | account-service, transfer-service | `application.yml` (ambos), los 5 `@KafkaListener` en `SagaEventListener.java`/`TransferSagaListener.java` | `antigravity-gemini-3.1-pro` | Toca la configuración de deserialización de los 2 servicios a la vez — un error rompe la saga completa en runtime sin fallo en compilación. |
| Task 12 | Medio | account-service | `AccountAtomicOperations.java`, `AccountAtomicOperationsImpl.java`, `SagaEventListener.java`, tests | `antigravity-gemini-3.1-pro` | Cambia la firma de las 3 operaciones atómicas centrales del servicio — mismo nivel de riesgo que la Task 3 de la feature `balance_ledger` original. |
| Task 13 | Bajo (agrupada) | account-service, transfer-service | `GlobalExceptionHandler.java` (ambos), `AccountServiceApplication.java`, `TransferServiceApplication.java`, `ProcessedEvent.java` | `antigravity-gemini-3-flash` | 3 fixes mecánicos sin relación lógica entre sí, agrupados en una sola tarea/PR por ser triviales y de bajo riesgo (ver Task Right-Sizing). |

**Oleada 1 (paralelo, sin dependencias entre sí): Tasks 1, 2, 3, 7, 9, 10, 12, 13**
**Oleada 2 (paralelo, depende de que Task 5 y Task 6 no compartan archivo con la Oleada 1 que ya mergeó — en la práctica pueden ir en la Oleada 1 también; se separan solo porque Task 5 y Task 6 comparten los mismos archivos de eventos de saga entre sí y NO deben correr en paralelo una de la otra):** Task 5, luego Task 6 (secuencial entre ellas — ambas tocan `SagaEventListener.java`/`TransferSagaListener.java`), en paralelo con Task 11 (toca los mismos archivos que 5/6 pero en las anotaciones de `@KafkaListener`, no en la lógica — **Task 11 debe esperar a que Task 5 y Task 6 estén mergeadas** para evitar conflictos de merge en los mismos métodos).
**Oleada 3 (depende de Task 1 en `develop`): Task 4** (ya definida abajo, sin cambios) y **Task 8** (depende de que Tasks 5/6/11 ya definieron los topics finales antes de tocar el script de creación de topics — en la práctica Task 8 puede ir en paralelo si coordina el nombre del topic nuevo de Task 6 de antemano; este plan asume que Task 6 crea el topic `compensation-results` y Task 8 lo incluye en su script sin conflicto real de archivo si se secuencia Task 6 → Task 8).

En resumen, el orden real de dependencia por archivo compartido es: **{1,2,3,7,9,10,12,13} en paralelo → 5 → 6 → {8,11} en paralelo → 4** (Task 4 sigue dependiendo solo de Task 1, puede adelantarse en cuanto Task 1 mergee, en paralelo con el resto).

**Setup de worktrees (ejemplo para la primera oleada, se repite el patrón para cada oleada):**

```bash
cd E:\Datos\Documentos\GitHub\API_Fidelizacion
git worktree add ../audit-fix-task1 -b feature/audit-fix-treasury-minbalance develop
git worktree add ../audit-fix-task2 -b feature/audit-fix-transactions-idor develop
git worktree add ../audit-fix-task3 -b feature/audit-fix-cors develop
git worktree add ../audit-fix-task7 -b feature/audit-fix-resilience4j develop
git worktree add ../audit-fix-task9 -b feature/audit-fix-limits-validation develop
git worktree add ../audit-fix-task10 -b feature/audit-fix-secrets-env develop
git worktree add ../audit-fix-task12 -b feature/audit-fix-atomic-reasons develop
git worktree add ../audit-fix-task13 -b feature/audit-fix-low-severity develop
```

**Lanzamiento de las sesiones OpenCode (en background, cada una en su propio worktree/`--dir`, mismo patrón para las 8 tareas de la oleada 1):**

```bash
opencode run "<prompt de la tarea>" --format json --title "<slug>" --dir "E:\Datos\Documentos\GitHub\API_Fidelizacion\..\audit-fix-taskN" --agent build --model <modelo de la tabla> > audit-fix-taskN.jsonl 2>&1 &
```

Cada sesión corre `mvn -pl <modulo> -am test` dentro de su propio worktree antes de reportar terminado (working directories aislados = sin pisarse los `target/` entre sí). Al terminar cada una, se extrae el `sessionID` de la primera línea del `.jsonl`, se **audita el diff manualmente** (yo, no el reporte del agente) corriendo los tests reales, y solo entonces se commitea/pushea/PR desde ese worktree.

**Orden de merge (secuencial, GitFlow normal, un PR aprobado a la vez, sin importar cuántas se desarrollaron en paralelo):** dentro de cada oleada el orden de merge entre tareas es indistinto (no comparten archivo), pero una oleada completa (todas sus tareas mergeadas) debe terminar antes de arrancar el worktree de la siguiente oleada, para que el `develop` de partida de esa oleada ya tenga los cambios de la anterior.

**Limpieza al terminar cada oleada:**

```bash
git worktree remove ../audit-fix-taskN   # repetir por cada worktree de la oleada
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

---

### Task 5: `SEED` se determina por origen real (tesorería), no por orden temporal (hallazgo alto #6)

**Hallazgo que resuelve:** `AccountAtomicOperationsImpl.creditIfActive` marca `SEED` cuando `ledgerRepository.countByAccountId(accountId) == 0` ("primer crédito que recibe esta cuenta"). La spec define `SEED` como "vino de `acc-treasury`". Dos usuarios que se transfieren puntos entre sí sin pasar nunca por tesorería etiquetan su primer crédito real como `SEED`, contaminando el kardex.

**Files:**
- Modify: `account-service/src/main/java/com/loyalty/account/saga/events/CreditRequestedEvent.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/saga/events/CreditRequestedEvent.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaPublisher.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaListener.java`
- Modify: `account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java`
- Modify: `account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperations.java`
- Modify: `account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperationsImpl.java`
- Test: `account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java`
- Test: `transfer-service/src/test/java/com/loyalty/transfer/saga/TransferSagaListenerIT.java` (si no existe con ese nombre, buscar el test IT real de `TransferSagaListener` antes de escribir — no asumir la ruta)

**Interfaces:**
- Consumes: `Transaction.getSourceAccountId()` (ya existente en `transfer-service`, disponible en `TransferSagaListener.onDebitResult` donde hoy se llama `publisher.publishCreditRequested(...)`).
- Produces: `CreditRequestedEvent` (ambos módulos) gana un campo `sourceAccountId`. `AccountAtomicOperations.creditIfActive` gana un parámetro `boolean fromTreasury`. Consumido únicamente dentro de esta tarea (no lo usa ninguna otra).

- [ ] **Step 1: Escribir el test que falla — crédito que NO viene de tesorería nunca es `SEED`, aunque sea el primero de la cuenta**

Modificar `AccountAtomicOperationsIT.java`, cambiar la firma de la llamada en `firstCreditIsRecordedAsSeedEventType` y `secondCreditIsRecordedAsCreditEventType` para pasar el nuevo parámetro, y agregar:
```java
    @Test
    void firstCreditFromNonTreasurySourceIsRecordedAsCreditNotSeed() {
        saveAccount("acc-8", 0L, 0L, AccountStatus.ACTIVE);

        boolean result = accountRepository.creditIfActive("acc-8", 50L, "tx-10", false);

        assertThat(result).isTrue();
        List<BalanceLedgerEntry> entries = ledgerRepository.findByAccountIdOrderByCreatedAtAsc("acc-8");
        assertThat(entries.get(0).getEventType()).isEqualTo(BalanceLedgerEventType.CREDIT);
    }
```
Y actualizar las llamadas existentes: `creditIfActive("acc-4", 100L, "tx-5", true)` (sí viene de tesorería → `SEED`), `creditIfActive("acc-5", 100L, "tx-6", true)` seguido de `creditIfActive("acc-5", 25L, "tx-7", false)` (el segundo sigue siendo `CREDIT`, ahora por regla explícita en vez de conteo).

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -pl account-service -am test -Dtest=AccountAtomicOperationsIT`
Expected: FAIL — `creditIfActive` no acepta un 4to parámetro todavía.

- [ ] **Step 3: Agregar `sourceAccountId` a `CreditRequestedEvent` en ambos módulos**

`account-service/.../saga/events/CreditRequestedEvent.java`:
```java
package com.loyalty.account.saga.events;

public record CreditRequestedEvent(String transactionId, String sourceAccountId, String targetAccountId, long amount, String timestamp) {
}
```
Mismo cambio en `transfer-service/.../saga/events/CreditRequestedEvent.java` (registro idéntico, es la copia local del mismo evento — ver hallazgo medio de Task 11 sobre esta duplicación, fuera de alcance aquí).

- [ ] **Step 4: Propagar `sourceAccountId` desde `TransferSagaPublisher`/`TransferSagaListener`**

`TransferSagaPublisher.publishCreditRequested`:
```java
    public void publishCreditRequested(String transactionId, String sourceAccountId, String targetAccountId, long amount) {
        kafkaTemplate.send("credit-events", transactionId,
                new CreditRequestedEvent(transactionId, sourceAccountId, targetAccountId, amount, Instant.now().toString()));
    }
```
`TransferSagaListener.onDebitResult`, la llamada:
```java
            publisher.publishCreditRequested(transaction.getId(), transaction.getSourceAccountId(), transaction.getTargetAccountId(), transaction.getAmount());
```

- [ ] **Step 5: `SagaEventListener.onCreditRequested` decide `fromTreasury` por el evento, no por el ledger**

Modificar la llamada en `account-service/.../saga/SagaEventListener.java`:
```java
        boolean fromTreasury = "acc-treasury".equals(event.sourceAccountId());
        boolean credited = accountRepository.creditIfActive(event.targetAccountId(), event.amount(), event.transactionId(), fromTreasury);
```

- [ ] **Step 6: Actualizar la interfaz e implementación de `AccountAtomicOperations`**

`AccountAtomicOperations.java`:
```java
    boolean creditIfActive(String accountId, long amount, String transactionId, boolean fromTreasury);
```
`AccountAtomicOperationsImpl.creditIfActive`, reemplazar la determinación del `eventType`:
```java
            BalanceLedgerEventType eventType = fromTreasury ? BalanceLedgerEventType.SEED : BalanceLedgerEventType.CREDIT;
```
(se elimina por completo la llamada a `ledgerRepository.countByAccountId(accountId)` para este propósito — puede seguir existiendo el método en el repositorio si otra tarea lo usa, pero ya no decide `SEED`).

- [ ] **Step 7: Ejecutar los tests y confirmar que pasan**

Run: `mvn -pl account-service -am test -Dtest=AccountAtomicOperationsIT`
Expected: PASS.

Run: `mvn -pl transfer-service -am test` (para confirmar que `TransferSagaPublisher`/`TransferSagaListener` compilan y sus tests existentes pasan con la firma nueva).
Expected: BUILD SUCCESS.

- [ ] **Step 8: Ejecutar toda la suite de `account-service`**

Run: `mvn -pl account-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/saga/events/CreditRequestedEvent.java transfer-service/src/main/java/com/loyalty/transfer/saga/events/CreditRequestedEvent.java transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaPublisher.java transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaListener.java account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperations.java account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperationsImpl.java account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java
git commit -m "fix: SEED se determina por origen real (acc-treasury), no por orden temporal del ledger"
```

---

### Task 6: Evento `CompensationApplied` confirma la reversión antes de marcar `FAILED` (hallazgo alto #7)

**Hallazgo que resuelve:** `SagaEventPublisher` (account-service) no tiene ningún método para confirmar que una compensación se aplicó. `TransferSagaListener.onCreditResult` marca `Transaction.FAILED` inmediatamente al recibir `CreditFailed`, en la misma llamada donde dispara `CompensateDebit`, sin esperar confirmación — ventana de inconsistencia donde el cliente ve `FAILED` antes de que el saldo de origen haya sido revertido.

**Files:**
- Create: `account-service/src/main/java/com/loyalty/account/saga/events/CompensationResultEvent.java`
- Create: `transfer-service/src/main/java/com/loyalty/transfer/saga/events/CompensationResultEvent.java`
- Modify: `account-service/src/main/java/com/loyalty/account/saga/SagaEventPublisher.java`
- Modify: `account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/domain/TransactionStatus.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaListener.java`
- Test: `account-service/src/test/java/com/loyalty/account/saga/SagaEventListenerIT.java`
- Test: el IT real de `TransferSagaListener` (confirmar ruta exacta antes de editar, ver Task 5)

**Interfaces:**
- Consumes: nada nuevo de otras tareas de este plan (independiente de Task 5 salvo que ambas tocan los mismos 2 archivos — por eso van secuenciales, no en paralelo, ver tabla de oleadas).
- Produces: topic Kafka nuevo `compensation-results`. `TransactionStatus` gana el valor `COMPENSATING`. No consumido por ninguna otra tarea.

- [ ] **Step 1: Escribir el test que falla — `Transaction` queda `COMPENSATING` (no `FAILED`) hasta recibir la confirmación**

En el test IT de `TransferSagaListener` (ruta a confirmar), agregar un caso: al recibir `CreditResultEvent.failed(...)`, la `Transaction` debe quedar en `COMPENSATING` (no `FAILED`), y solo al recibir después un `CompensationResultEvent` exitoso para el mismo `transactionId` debe pasar a `FAILED`.

En `SagaEventListenerIT.java` (account-service), agregar un caso: al recibir un `CompensateDebitEvent`, además de `creditUnconditionally`, debe publicarse un `CompensationResultEvent` con `success=true` para ese `transactionId` (verificar consumiendo del topic `compensation-results` con el cliente Kafka de test, mismo patrón que el resto del archivo).

- [ ] **Step 2: Ejecutar ambos tests y confirmar que fallan**

Run: `mvn -pl account-service -am test -Dtest=SagaEventListenerIT` y el equivalente en `transfer-service`.
Expected: FAIL — no existe `CompensationResultEvent` ni el estado `COMPENSATING`.

- [ ] **Step 3: Crear `CompensationResultEvent` en ambos módulos**

```java
package com.loyalty.account.saga.events;

public record CompensationResultEvent(String transactionId, boolean success) {

    public static CompensationResultEvent applied(String transactionId) {
        return new CompensationResultEvent(transactionId, true);
    }
}
```
(copia idéntica en `transfer-service/.../saga/events/CompensationResultEvent.java`, mismo paquete relativo).

- [ ] **Step 4: Publicar el evento tras `creditUnconditionally` en `SagaEventListener.onCompensateDebit`**

```java
    @KafkaListener(topics = "transfer-compensation", groupId = "account-service-compensation",
            properties = "spring.json.value.default.type:com.loyalty.account.saga.events.CompensateDebitEvent")
    public void onCompensateDebit(CompensateDebitEvent event) {
        String idempotencyKey = event.transactionId() + ":COMPENSATION";
        if (!tryMarkProcessed(idempotencyKey)) {
            return;
        }
        accountRepository.creditUnconditionally(event.sourceAccountId(), event.amount(), event.transactionId());
        publisher.publishCompensationResult(CompensationResultEvent.applied(event.transactionId()));
    }
```
Agregar a `SagaEventPublisher`:
```java
    public void publishCompensationResult(CompensationResultEvent event) {
        kafkaTemplate.send("compensation-results", event.transactionId(), event);
    }
```

- [ ] **Step 5: Agregar `COMPENSATING` a `TransactionStatus` y cambiar `TransferSagaListener`**

`TransactionStatus.java`: agregar el valor `COMPENSATING` al enum (junto a `PENDING`, `COMPLETED`, `FAILED`, revisar el archivo real antes de asumir los valores exactos existentes).

`TransferSagaListener.onCreditResult`, reemplazar:
```java
        } else {
            publisher.publishCompensateDebit(transaction.getId(), transaction.getSourceAccountId(), transaction.getAmount());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason(event.reason());
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        }
```
por:
```java
        } else {
            publisher.publishCompensateDebit(transaction.getId(), transaction.getSourceAccountId(), transaction.getAmount());
            transaction.setStatus(TransactionStatus.COMPENSATING);
            transaction.setFailureReason(event.reason());
            transactionRepository.save(transaction);
        }
```
Y agregar un nuevo listener:
```java
    @KafkaListener(topics = "compensation-results", groupId = "transfer-service-compensation-results",
            properties = "spring.json.value.default.type:com.loyalty.transfer.saga.events.CompensationResultEvent")
    public void onCompensationResult(CompensationResultEvent event) {
        Transaction transaction = transactionRepository.findById(event.transactionId()).orElse(null);
        if (transaction == null || transaction.getStatus() != TransactionStatus.COMPENSATING) {
            return;
        }
        if (event.success()) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setCompletedAt(Instant.now());
            transactionRepository.save(transaction);
        }
    }
```

- [ ] **Step 6: Agregar el topic nuevo al script de creación**

En `docker/kafka/create-topics.sh`, agregar `compensation-results` a la lista de topics (coordinación con Task 8, que también toca este archivo — Task 6 debe mergear primero, ver tabla de oleadas).

- [ ] **Step 7: Ejecutar ambos tests y confirmar que pasan**

Run: `mvn -pl account-service -am test -Dtest=SagaEventListenerIT` y el IT de `TransferSagaListener` en `transfer-service`.
Expected: PASS.

- [ ] **Step 8: Ejecutar toda la suite de ambos módulos**

Run: `mvn -pl account-service -am test` y `mvn -pl transfer-service -am test`.
Expected: BUILD SUCCESS en ambos.

- [ ] **Step 9: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/saga/ transfer-service/src/main/java/com/loyalty/transfer/saga/ transfer-service/src/main/java/com/loyalty/transfer/domain/TransactionStatus.java docker/kafka/create-topics.sh account-service/src/test/java/com/loyalty/account/saga/SagaEventListenerIT.java
git commit -m "feat: agrega CompensationResultEvent para confirmar la reversion antes de marcar FAILED"
```

---

### Task 7: Resilience4j en la comunicación síncrona inter-servicio (hallazgo alto #8)

**Hallazgo que resuelve:** `TransferClient` (account-service) y `AccountClient` (transfer-service) usan `RestClient` plano sin timeout ni circuit breaker. Si el servicio downstream se degrada, el llamador bloquea threads indefinidamente.

**Files:**
- Modify: `account-service/pom.xml`
- Modify: `transfer-service/pom.xml`
- Modify: `account-service/src/main/java/com/loyalty/account/client/TransferClient.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/client/AccountClient.java`
- Modify: `account-service/src/main/resources/application.yml`
- Modify: `transfer-service/src/main/resources/application.yml`
- Test: `account-service/src/test/java/com/loyalty/account/client/TransferClientTest.java`
- Test: la contraparte real en `transfer-service` para `AccountClient` (confirmar si existe antes de editar)

**Interfaces:**
- Consumes: nada de otras tareas.
- Produces: nada consumido por otras tareas de este plan.

- [ ] **Step 1: Agregar la dependencia a ambos `pom.xml`**

```xml
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
    </dependency>
```
(la versión la resuelve el BOM `spring-cloud-dependencies` ya importado en el `pom.xml` raíz — no fijar versión explícita).

- [ ] **Step 2: Escribir el test que falla — timeout configurado se respeta**

En `TransferClientTest.java` (revisar el test actual primero — usa `MockWebServer` según el `pom.xml`), agregar un caso que verifique que una respuesta que tarda más que el timeout configurado lanza una excepción de timeout en vez de colgar indefinidamente (usar `mockWebServer.enqueue(new MockResponse().setBodyDelay(...))`, patrón estándar de `mockwebserver` ya presente como dependencia).

- [ ] **Step 3: Ejecutar el test y confirmar que falla**

Run: `mvn -pl account-service -am test -Dtest=TransferClientTest`
Expected: FAIL o timeout del propio test (sin límite configurado, la llamada no falla rápido).

- [ ] **Step 4: Configurar timeouts explícitos en los builders de `RestClient`**

`TransferClient.java` y `AccountClient.java`, cambiar el constructor `@Autowired` para usar un `ClientHttpRequestFactorySettings`/`ClientHttpRequestFactory` con timeouts:
```java
    @Autowired
    public TransferClient(RestClient.Builder restClientBuilder) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(5));
        this.restClient = restClientBuilder
                .baseUrl("http://transfer-service")
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }
```
(mismo patrón simétrico en `AccountClient`, apuntando a `http://account-service`).

- [ ] **Step 5: Envolver las llamadas con `@CircuitBreaker`**

Agregar `@CircuitBreaker(name = "transferService", fallbackMethod = "listTransactionsFallback")` sobre `TransferClient.listTransactions`, y el método de fallback que relance una excepción de negocio clara (`TransferServiceUnavailableException` o similar, nueva clase mínima) en vez de dejar propagar la excepción cruda de Resilience4j. Simétrico en `AccountClient.fetchAccount`.

En `application.yml` de ambos servicios, agregar la configuración básica del circuit breaker:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      transferService:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
      accountService:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -pl account-service -am test -Dtest=TransferClientTest`
Expected: PASS.

- [ ] **Step 7: Ejecutar toda la suite de ambos módulos**

Run: `mvn -pl account-service -am test` y `mvn -pl transfer-service -am test`.
Expected: BUILD SUCCESS en ambos — prestar especial atención a que los ITs que sí dependen de la comunicación real entre servicios (`AccountTransactionsControllerIT`, etc.) sigan pasando con los nuevos timeouts (no deberían activarse en un entorno de test rápido, pero confirmar).

- [ ] **Step 8: Commit**

```bash
git add account-service/pom.xml transfer-service/pom.xml account-service/src/main/java/com/loyalty/account/client/TransferClient.java transfer-service/src/main/java/com/loyalty/transfer/client/AccountClient.java account-service/src/main/resources/application.yml transfer-service/src/main/resources/application.yml account-service/src/test/java/com/loyalty/account/client/TransferClientTest.java
git commit -m "feat: agrega Resilience4j (timeouts + circuit breaker) a la comunicacion sincrona inter-servicio"
```

---

### Task 8: Endurecimiento de red — Kafka con SASL/SCRAM+ACLs y puertos internos sin exponer al host (hallazgos alto #5 + medio "puertos expuestos")

**Hallazgo que resuelve:** Kafka corre en `PLAINTEXT` sin ACLs — cualquier actor con acceso de red al broker puede publicar eventos falsos en cualquier topic. Todos los servicios (incluido Mongo sin auth) exponen su puerto al host, permitiendo bypasear el gateway y alcanzar Kafka/Mongo directo desde fuera de la red Docker.

**Files:**
- Modify: `docker-compose.yml`
- Create: `docker/kafka/create-acls.sh`
- Modify: `docker/kafka/create-topics.sh` (agregar `compensation-results` si Task 6 ya mergeó, coordinar orden)
- Modify: `account-service/src/main/resources/application.yml`
- Modify: `transfer-service/src/main/resources/application.yml`

**Interfaces:**
- Consumes: la lista de topics real (post Task 6).
- Produces: nada consumido por otras tareas — es el último eslabón de infraestructura.

- [ ] **Step 1: Quitar los mapeos de puerto al host de todo lo que no sea `gateway`**

En `docker-compose.yml`, eliminar el bloque `ports:` de `mongo`, `account-service`, `transfer-service`, `auth-service`, `eureka-server`, y de `kafka` dejar solo el listener `EXTERNAL` si se necesita depurar puntualmente (o quitarlo también y usar `docker compose exec` para depuración). Mantener el `ports:` de `gateway` (8080) y de `keycloak` (necesario para que Keycloak redirija bien en `docker-compose` local — confirmar en `ARQUITECTURA.md` si hay una razón documentada para mantenerlo antes de tocarlo).

- [ ] **Step 2: Configurar Kafka con SASL_PLAINTEXT + SCRAM-SHA-256**

En el servicio `kafka` de `docker-compose.yml`, cambiar:
```yaml
    environment:
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: SASL_PLAINTEXT://kafka:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,SASL_PLAINTEXT:SASL_PLAINTEXT
      KAFKA_SASL_ENABLED_MECHANISMS: SCRAM-SHA-256
      KAFKA_SASL_MECHANISM_INTER_BROKER_PROTOCOL: SCRAM-SHA-256
      KAFKA_INTER_BROKER_LISTENER_NAME: SASL_PLAINTEXT
      KAFKA_LISTENER_NAME_SASL_PLAINTEXT_SCRAM___SHA___256_SASL_JAAS_CONFIG: >-
        org.apache.kafka.common.security.scram.ScramLoginModule required
        username="admin" password="admin-secret";
      KAFKA_SUPER_USERS: User:admin
      KAFKA_AUTHORIZER_CLASS_NAME: org.apache.kafka.metadata.authorizer.StandardAuthorizer
      KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND: "false"
```
(nota para el ejecutor: KRaft + SCRAM requiere crear las credenciales SCRAM vía `kafka-storage.sh format --add-scram` en el arranque, o vía `kafka-configs.sh --bootstrap-server ... --alter --add-config` después de que el broker esté healthy pero antes de crear ACLs — **esta tarea requiere validación contra el stack real levantado, no solo lectura de código**; si el enfoque `--add-scram` en format falla en la práctica, la alternativa documentada de Apache Kafka es crear el usuario SCRAM post-arranque con `kafka-configs.sh` antes de que `kafka-topics-init`/`kafka-acls-init` corran).

- [ ] **Step 3: Crear `docker/kafka/create-acls.sh`**

```sh
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
```

- [ ] **Step 4: Configurar credenciales SASL en los `application.yml` de ambos servicios**

```yaml
  kafka:
    bootstrap-servers: kafka:9092
    security:
      protocol: SASL_PLAINTEXT
    properties:
      sasl.mechanism: SCRAM-SHA-256
      sasl.jaas.config: org.apache.kafka.common.security.scram.ScramLoginModule required username="account-service" password="${KAFKA_ACCOUNT_SERVICE_PASSWORD:account-service-secret}";
```
(análogo en `transfer-service` con su propio usuario/password).

- [ ] **Step 5: Validar contra el stack real**

Este es el único paso de este plan que **requiere** `docker-compose up -d --build` y observación real de logs — no puede darse por verificado solo leyendo el diff. Ejecutar:
```bash
docker-compose up -d --build
docker-compose logs kafka kafka-topics-init account-service transfer-service --tail=50
```
Confirmar que ambos servicios se conectan a Kafka sin errores de autenticación, que los topics se crean, y que las ACLs quedan aplicadas (`docker-compose exec kafka /opt/kafka/bin/kafka-acls.sh --bootstrap-server localhost:9092 --command-config /tmp/admin.properties --list`).

- [ ] **Step 6: Correr la suite E2E completa contra el stack endurecido**

Run: `mvn -pl e2e-tests test -Dmaven.test.skip=false`
Expected: 6/6 PASS — si algo falla aquí, es señal de que la configuración SASL/ACL bloqueó tráfico legítimo; ajustar antes de continuar.

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml docker/kafka/create-acls.sh docker/kafka/create-topics.sh account-service/src/main/resources/application.yml transfer-service/src/main/resources/application.yml
git commit -m "feat: Kafka con SASL/SCRAM+ACLs por servicio, cierra puertos internos innecesarios al host"
```

---

### Task 9: Validación en `PATCH /accounts/{id}/limits` (hallazgo medio)

**Hallazgo que resuelve:** El endpoint no tiene `@Valid` y `UpdateLimitsRequest.minBalance` no tiene ninguna restricción — un `ADMIN` puede fijar cualquier valor.

**Files:**
- Modify: `account-service/src/main/java/com/loyalty/account/dto/UpdateLimitsRequest.java`
- Modify: `account-service/src/main/java/com/loyalty/account/controller/AccountController.java`
- Test: `account-service/src/test/java/com/loyalty/account/controller/AccountLimitsControllerIT.java`

**Interfaces:**
- Consumes/Produces: nada compartido con otras tareas.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `AccountLimitsControllerIT.java`:
```java
    @Test
    void rejectsRequestWithoutMinBalance() throws Exception {
        mockMvc.perform(patch("/accounts/acc-limits-test/limits")
                        .with(jwt().jwt(j -> j.subject("admin-1")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `mvn -pl account-service -am test -Dtest=AccountLimitsControllerIT`
Expected: FAIL — hoy `{}` es aceptado (`200`), `minBalance` queda `null` sin validación.

- [ ] **Step 3: Agregar la anotación al DTO y `@Valid` al controller**

`UpdateLimitsRequest.java`:
```java
package com.loyalty.account.dto;

import jakarta.validation.constraints.NotNull;

public class UpdateLimitsRequest {

    @NotNull
    private Long minBalance;

    public Long getMinBalance() {
        return minBalance;
    }

    public void setMinBalance(Long minBalance) {
        this.minBalance = minBalance;
    }
}
```
`AccountController.updateLimits`:
```java
    public ResponseEntity<AccountResponse> updateLimits(@PathVariable("id") String id,
                                                         @Valid @RequestBody UpdateLimitsRequest request) {
```

- [ ] **Step 4: Ejecutar y confirmar que pasa**

Run: `mvn -pl account-service -am test -Dtest=AccountLimitsControllerIT`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/dto/UpdateLimitsRequest.java account-service/src/main/java/com/loyalty/account/controller/AccountController.java account-service/src/test/java/com/loyalty/account/controller/AccountLimitsControllerIT.java
git commit -m "fix: exige minBalance no nulo con @Valid en PATCH /accounts/{id}/limits"
```

---

### Task 10: Secretos vía variable de entorno con default de desarrollo (hallazgo medio)

**Hallazgo que resuelve:** `admin-client-secret`/`login-client-secret` y `KEYCLOAK_ADMIN_PASSWORD` están hardcodeados en texto plano, sin indirección — nada impide que el mismo literal llegue a un despliegue real.

**Files:**
- Modify: `auth-service/src/main/resources/application.yml`
- Modify: `docker-compose.yml`
- Modify: `docker/keycloak/loyalty-realm.json` (solo si soporta variables — Keycloak realm import no soporta interpolación de env vars nativamente; ver Step 2)

**Interfaces:**
- Consumes/Produces: nada compartido con otras tareas.

- [ ] **Step 1: Cambiar `auth-service/application.yml` a variables de entorno con default**

```yaml
  admin-client-secret: ${KEYCLOAK_ADMIN_CLIENT_SECRET:loyalty-app-dev-secret}
  login-client-secret: ${KEYCLOAK_LOGIN_CLIENT_SECRET:loyalty-app-dev-secret}
```

- [ ] **Step 2: Cambiar `docker-compose.yml`**

```yaml
    environment:
      KEYCLOAK_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:-admin}
```
Para `auth-service`, propagar las mismas env vars al contenedor:
```yaml
  auth-service:
    environment:
      KEYCLOAK_ADMIN_CLIENT_SECRET: ${KEYCLOAK_ADMIN_CLIENT_SECRET:-loyalty-app-dev-secret}
      KEYCLOAK_LOGIN_CLIENT_SECRET: ${KEYCLOAK_LOGIN_CLIENT_SECRET:-loyalty-app-dev-secret}
```

- [ ] **Step 3: Documentar la limitación de `loyalty-realm.json`**

El import de realm de Keycloak (`docker/keycloak/loyalty-realm.json`) no soporta interpolación de variables de entorno de forma nativa en el JSON — el client secret ahí seguirá siendo el literal de desarrollo. Agregar un comentario/nota en `ARQUITECTURA.md` §Riesgos (si no está ya) aclarando que el secreto real de Keycloak para ese client debe rotarse manualmente vía Admin Console/API en cualquier entorno que no sea desarrollo local, ya que el archivo de import no es el mecanismo para eso en producción.

- [ ] **Step 4: Ejecutar `mvn -pl auth-service -am test` para confirmar que no rompió nada**

Run: `mvn -pl auth-service -am test`
Expected: BUILD SUCCESS (los tests no dependen del valor literal del secreto, solo de que la property exista).

- [ ] **Step 5: Commit**

```bash
git add auth-service/src/main/resources/application.yml docker-compose.yml docs/ARQUITECTURA.md
git commit -m "fix: secretos de Keycloak via variable de entorno con default de desarrollo"
```

---

### Task 11: Serialización Kafka vía `TYPE_MAPPINGS` en vez de FQCN hardcodeado (hallazgo medio)

**Hallazgo que resuelve:** Cada `@KafkaListener` fija `spring.json.value.default.type:<FQCN>` como string — un rename de paquete rompe el consumo en runtime sin error visible hasta que llega un mensaje.

**Depende de:** Task 5 y Task 6 ya mergeadas (tocan los mismos métodos `@KafkaListener` que esta tarea debe modificar — evitar conflictos de merge editando después).

**Files:**
- Modify: `account-service/src/main/resources/application.yml`
- Modify: `transfer-service/src/main/resources/application.yml`
- Modify: `account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java` (quitar `properties =` de cada `@KafkaListener`)
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaListener.java` (ídem)

**Interfaces:**
- Consumes: los eventos ya definidos por Task 5/Task 6 (`CreditRequestedEvent` con `sourceAccountId`, `CompensationResultEvent`).
- Produces: nada consumido por otras tareas.

- [ ] **Step 1: Escribir el test que falla — un mensaje con el alias corto se deserializa al tipo correcto**

En `SagaEventListenerIT.java`, si el test actual publica eventos usando `KafkaTemplate<String, Object>` con serialización automática de tipo por header (lo cual cambia con esta tarea), confirmar que sigue funcionando: el `TYPE_MAPPINGS` debe cubrir el alias tanto en el producer como en el consumer de cada topic. No se necesita un test nuevo si los ITs existentes siguen pasando — este paso es principalmente de configuración, la señal de "falla" es que los ITs existentes fallen tras el cambio de config si el mapeo queda incompleto.

- [ ] **Step 2: Configurar `TYPE_MAPPINGS` en `application.yml` de ambos servicios**

`account-service/application.yml`, reemplazar el bloque `consumer.properties`:
```yaml
    consumer:
      group-id: account-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.loyalty.*"
        spring.json.use.type.headers: false
        spring.json.type.mapping: >-
          debitRequested:com.loyalty.account.saga.events.DebitRequestedEvent,
          creditRequested:com.loyalty.account.saga.events.CreditRequestedEvent,
          compensateDebit:com.loyalty.account.saga.events.CompensateDebitEvent
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        spring.json.add.type.headers: false
```
(nota: se restringe además `spring.json.trusted.packages` de `"*"` a `"com.loyalty.*"` — cierra de paso el hallazgo medio de deserialización insegura que Gemini-3.1-Pro señaló en la primera pasada de auditoría, mismo archivo).

Simétrico en `transfer-service/application.yml` con los alias `debitResult`, `creditResult`, `compensationResult` apuntando a las clases de evento de `transfer-service`.

- [ ] **Step 3: Quitar el `properties =` de cada `@KafkaListener`**

En `SagaEventListener.java` y `TransferSagaListener.java`, cada anotación pasa de:
```java
    @KafkaListener(topics = "debit-events", groupId = "account-service-debit",
            properties = "spring.json.value.default.type:com.loyalty.account.saga.events.DebitRequestedEvent")
```
a:
```java
    @KafkaListener(topics = "debit-events", groupId = "account-service-debit")
```
(el tipo ahora lo resuelve `spring.json.type.mapping` centralizado en `application.yml`, ya no hardcodeado por listener).

- [ ] **Step 4: Ejecutar toda la suite de ambos módulos**

Run: `mvn -pl account-service -am test` y `mvn -pl transfer-service -am test`.
Expected: BUILD SUCCESS en ambos, en particular los `*IT` con Kafka embebido (`SagaEventListenerIT`, el IT de `TransferSagaListener`).

- [ ] **Step 5: Commit**

```bash
git add account-service/src/main/resources/application.yml transfer-service/src/main/resources/application.yml account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java transfer-service/src/main/java/com/loyalty/transfer/saga/TransferSagaListener.java
git commit -m "fix: TYPE_MAPPINGS centralizado en vez de FQCN hardcodeado por listener, acota trusted.packages"
```

---

### Task 12: Motivo de fallo específico en vez de siempre genérico (hallazgo medio)

**Hallazgo que resuelve:** `SagaEventListener.onDebitRequested`/`onCreditRequested` publican siempre `"INSUFFICIENT_BALANCE"`/`"TARGET_INACTIVE"` como motivo, sin distinguir "cuenta no existe" de "inactiva" de "no cumple `minBalance"`, aunque la lógica interna de `AccountAtomicOperationsImpl` sí distingue esos casos.

**Files:**
- Modify: `account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperations.java`
- Modify: `account-service/src/main/java/com/loyalty/account/repository/AccountAtomicOperationsImpl.java`
- Modify: `account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java`
- Test: `account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java`
- Test: `account-service/src/test/java/com/loyalty/account/saga/SagaEventListenerIT.java`

**Interfaces:**
- Consumes: nada de otras tareas (independiente de Task 5, aunque toca los mismos archivos — por eso Task 12 va en la Oleada 1, antes de Task 5/6; si el orden real de ejecución termina siendo distinto, resolver el conflicto de merge trivial entre el 4to parámetro de Task 5 y el tipo de retorno enriquecido de esta tarea manualmente al mergear la segunda de las dos).
- Produces: `AccountAtomicOperations.debitIfSufficientBalance`/`creditIfActive` devuelven un `record DebitOutcome(boolean success, String reason)` en vez de `boolean`. Consumido solo por `SagaEventListener` dentro de esta misma tarea.

- [ ] **Step 1: Escribir el test que falla**

Agregar a `AccountAtomicOperationsIT.java`:
```java
    @Test
    void debitReturnsAccountNotFoundReasonWhenAccountDoesNotExist() {
        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-nope", 10L, "tx-11");

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.reason()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void debitReturnsSourceInactiveReasonWhenAccountInactive() {
        saveAccount("acc-9", 100L, 0L, AccountStatus.INACTIVE);

        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-9", 10L, "tx-12");

        assertThat(outcome.reason()).isEqualTo("SOURCE_INACTIVE");
    }

    @Test
    void debitReturnsInsufficientBalanceReasonWhenMinBalanceViolated() {
        saveAccount("acc-10", 10L, 0L, AccountStatus.ACTIVE);

        DebitOutcome outcome = accountRepository.debitIfSufficientBalance("acc-10", 40L, "tx-13");

        assertThat(outcome.reason()).isEqualTo("INSUFFICIENT_BALANCE");
    }
```
(y actualizar todas las llamadas existentes en este archivo que hoy esperan `boolean` para usar `.success()`).

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `mvn -pl account-service -am test -Dtest=AccountAtomicOperationsIT`
Expected: FAIL — el tipo de retorno sigue siendo `boolean`.

- [ ] **Step 3: Crear el record `DebitOutcome` y actualizar la interfaz**

```java
package com.loyalty.account.repository;

public record DebitOutcome(boolean success, String reason) {

    public static DebitOutcome success() {
        return new DebitOutcome(true, null);
    }

    public static DebitOutcome failure(String reason) {
        return new DebitOutcome(false, reason);
    }
}
```
`AccountAtomicOperations.java`:
```java
    DebitOutcome debitIfSufficientBalance(String accountId, long amount, String transactionId);
```
(`creditIfActive` puede quedar igual con `boolean` si no aplica la misma diferenciación — revisar si la spec pide diferenciar también el motivo de crédito fallido; si no, solo `debitIfSufficientBalance` cambia).

- [ ] **Step 4: Actualizar `AccountAtomicOperationsImpl.debitIfSufficientBalance`**

```java
    @Override
    @Transactional("transactionManager")
    public DebitOutcome debitIfSufficientBalance(String accountId, long amount, String transactionId) {
        Account before = mongoTemplate.findById(accountId, Account.class);
        if (before == null) {
            return DebitOutcome.failure("ACCOUNT_NOT_FOUND");
        }
        if (before.getStatus() != AccountStatus.ACTIVE) {
            return DebitOutcome.failure("SOURCE_INACTIVE");
        }

        // ... (el resto de la logica de $expr/updateFirst igual que hoy) ...

        boolean applied = mongoTemplate.updateFirst(query, update, Account.class).getModifiedCount() == 1;
        if (applied) {
            // ... (igual que hoy, insertar en el ledger) ...
            return DebitOutcome.success();
        }
        return DebitOutcome.failure("INSUFFICIENT_BALANCE");
    }
```

- [ ] **Step 5: Actualizar `SagaEventListener.onDebitRequested`**

```java
        DebitOutcome outcome = accountRepository.debitIfSufficientBalance(event.sourceAccountId(), event.amount(), event.transactionId());
        if (outcome.success()) {
            publisher.publishDebitResult(DebitResultEvent.succeeded(event.transactionId()));
        } else {
            publisher.publishDebitResult(DebitResultEvent.failed(event.transactionId(), outcome.reason()));
        }
```

- [ ] **Step 6: Ejecutar los tests y confirmar que pasan**

Run: `mvn -pl account-service -am test -Dtest=AccountAtomicOperationsIT,SagaEventListenerIT`
Expected: PASS.

- [ ] **Step 7: Ejecutar toda la suite de `account-service`**

Run: `mvn -pl account-service -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/repository/ account-service/src/main/java/com/loyalty/account/saga/SagaEventListener.java account-service/src/test/java/com/loyalty/account/repository/AccountAtomicOperationsIT.java account-service/src/test/java/com/loyalty/account/saga/SagaEventListenerIT.java
git commit -m "fix: debitIfSufficientBalance devuelve el motivo de fallo especifico en vez de un boolean generico"
```

---

### Task 13: Correcciones de severidad baja agrupadas (catch-all, `@EnableDiscoveryClient`, índice `ProcessedEvent`)

**Hallazgo que resuelve:** (a) sin `@ExceptionHandler(Exception.class)` de respaldo en ninguno de los 2 `GlobalExceptionHandler`; (b) `@EnableDiscoveryClient` redundante en Spring Cloud 2023 (el auto-registro ya ocurre solo con la dependencia); (c) `ProcessedEvent` sin índice/TTL, crecerá indefinidamente.

**Files:**
- Modify: `account-service/src/main/java/com/loyalty/account/exception/GlobalExceptionHandler.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/exception/GlobalExceptionHandler.java`
- Modify: `account-service/src/main/java/com/loyalty/account/AccountServiceApplication.java`
- Modify: `transfer-service/src/main/java/com/loyalty/transfer/TransferServiceApplication.java`
- Modify: `account-service/src/main/java/com/loyalty/account/saga/ProcessedEvent.java`
- Test: `account-service/src/test/java/com/loyalty/account/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes/Produces: nada compartido con otras tareas.

- [ ] **Step 1: Escribir el test que falla — una excepción no controlada devuelve 500 con el formato estándar**

Agregar a `GlobalExceptionHandlerTest.java`:
```java
    @Test
    void handlesUnexpectedExceptionWithGenericFormat() {
        RuntimeException ex = new RuntimeException("detalle interno sensible");

        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getMessage()).doesNotContain("detalle interno sensible");
    }
```
(confirmar el nombre real de la clase de respuesta de error, `ErrorResponse` es un supuesto — revisar el archivo actual antes de escribir el test).

- [ ] **Step 2: Ejecutar y confirmar que falla**

Run: `mvn -pl account-service -am test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — `handleUnexpected` no existe.

- [ ] **Step 3: Agregar el handler catch-all en ambos servicios**

```java
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Error no controlado", ex);
        return ResponseEntity.status(500).body(new ErrorResponse("INTERNAL_ERROR", "Ocurrio un error inesperado", Instant.now()));
    }
```
(agregar un logger `private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);` si no existe ya en la clase; usar el constructor real de `ErrorResponse` del archivo, no inventar uno).

- [ ] **Step 4: Quitar `@EnableDiscoveryClient` de ambas aplicaciones**

En `AccountServiceApplication.java` y `TransferServiceApplication.java`, quitar la anotación `@EnableDiscoveryClient` y su import (`spring-cloud-starter-netflix-eureka-client` sigue registrando el servicio automáticamente solo con estar en el classpath, sin la anotación).

- [ ] **Step 5: Agregar TTL al índice de `ProcessedEvent`**

```java
package com.loyalty.account.saga;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document(collection = "processed_events")
public class ProcessedEvent {

    @Id
    private String id;

    @Indexed(expireAfterSeconds = 604800) // 7 dias
    private Instant createdAt = Instant.now();

    public ProcessedEvent() {
    }

    public ProcessedEvent(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```
(el índice TTL de Mongo purga documentos automáticamente 7 días después de `createdAt` — ajustable según cuánto se necesite retener el historial de idempotencia).

- [ ] **Step 6: Ejecutar los tests y confirmar que pasan**

Run: `mvn -pl account-service -am test -Dtest=GlobalExceptionHandlerTest` y `mvn -pl transfer-service -am test`.
Expected: PASS / BUILD SUCCESS.

- [ ] **Step 7: Ejecutar toda la suite de ambos módulos**

Run: `mvn -pl account-service -am test` y `mvn -pl transfer-service -am test`.
Expected: BUILD SUCCESS en ambos.

- [ ] **Step 8: Commit**

```bash
git add account-service/src/main/java/com/loyalty/account/exception/GlobalExceptionHandler.java transfer-service/src/main/java/com/loyalty/transfer/exception/GlobalExceptionHandler.java account-service/src/main/java/com/loyalty/account/AccountServiceApplication.java transfer-service/src/main/java/com/loyalty/transfer/TransferServiceApplication.java account-service/src/main/java/com/loyalty/account/saga/ProcessedEvent.java account-service/src/test/java/com/loyalty/account/exception/GlobalExceptionHandlerTest.java
git commit -m "fix: exception handler catch-all, quita @EnableDiscoveryClient redundante, TTL en ProcessedEvent"
```

---

## Self-Review

**1. Cobertura de hallazgos — TODOS los reportados por las 4 auditorías quedan cubiertos, salvo 1 descartado por inexacto:**

| # | Hallazgo | Severidad | Tarea |
|---|---|---|---|
| 1 | `minBalance` ignorado en `TransferServiceImpl` | Crítico | Task 1 |
| 2 | Suite E2E rota (contrato `POST /accounts`) | Crítico | Task 4 |
| 3 | IDOR en `GET /transactions` | Crítico | Task 2 |
| 4 | CORS comodín + credenciales | Crítico | Task 3 |
| 5 | Confianza ciega en eventos Kafka (sin ACLs/mTLS) | Alto | Task 8 |
| 6 | `SEED` inferido por orden temporal, no por origen real | Alto | Task 5 |
| 7 | Falta `CompensationApplied`, `FAILED` prematuro | Alto | Task 6 |
| 8 | Sin Resilience4j en comunicación síncrona | Alto | Task 7 |
| 9 | `PATCH /accounts/{id}/limits` sin `@Valid` | Medio | Task 9 |
| 10 | Secreto Keycloak hardcodeado sin indirección | Medio | Task 10 |
| 11 | Puertos innecesarios expuestos al host | Medio | Task 8 (mismo archivo que #5, se resuelven juntos) |
| 12 | Serialización Kafka frágil (FQCN hardcodeado) | Medio | Task 11 |
| 13 | Motivo de fallo siempre genérico en la saga | Medio | Task 12 |
| 14 | Sin `GlobalExceptionHandler` catch-all | Bajo | Task 13 |
| 15 | `@EnableDiscoveryClient` redundante | Bajo | Task 13 |
| 16 | `ProcessedEvent` sin índice/TTL | Bajo | Task 13 |
| — | `@Autowired` como "inyección por campo" (`TransferClient`/`AccountClient`) | Bajo (reportado) | **Descartado** — verificado que ya usa inyección por constructor (patrón de doble constructor: uno `@Autowired` para producción, uno plano para tests), no inyección por campo. El hallazgo original de OpenCode/Gemini-3-Flash fue impreciso; no se implementa ningún cambio porque no hay nada que corregir. |

16 de 17 hallazgos reportados cubiertos por una tarea concreta; el restante fue verificado como falso positivo y documentado como tal (no simplemente omitido en silencio).

**2. Placeholders:** Task 4 Step 1 y Step 2 son deliberadamente menos prescriptivos que el resto (piden leer los fixtures reales antes de escribir código) porque este plan no releyó el contenido completo de `AuthTestFixture`/`ApiClient`/`HappyPathE2ETest` en esta sesión. Task 5 Step 1 y Task 6 Step 1 piden confirmar la ruta exacta del IT de `TransferSagaListener` antes de editar (no releído en esta sesión de auditoría). Task 7 Step 2 pide confirmar si existe un test IT real de `AccountClient` en `transfer-service` antes de asumir su ausencia. Estas son las únicas excepciones al principio de "no placeholders" en todo el plan, cada una justificada explícitamente (evitar inventar una firma/ruta que rompa la compilación) en vez de dejarse como una omisión silenciosa. El ejecutor de cada una de esas tareas debe resolver esa lectura como primer paso obligatorio.

**3. Consistencia de tipos entre tareas:**
- `AccountView.getMinBalance()`/`setMinBalance(Long)` (Task 1) no lo consume ninguna otra tarea de este plan.
- `CreditRequestedEvent` (ambos módulos, Task 5) gana `sourceAccountId` — Task 11 toca el mismo archivo después (oleada posterior) solo para quitar `properties=` del `@KafkaListener`, sin tocar el record en sí; sin conflicto de tipos.
- `AccountAtomicOperations.creditIfActive` gana un 4to parámetro `boolean fromTreasury` (Task 5) y `debitIfSufficientBalance` cambia su tipo de retorno a `DebitOutcome` (Task 12) — son 2 métodos distintos de la misma interfaz, sin colisión de firma entre sí, pero **ambas tareas tocan el mismo archivo `AccountAtomicOperations.java`/`AccountAtomicOperationsImpl.java`** — de ahí que Task 12 esté en la Oleada 1 (antes) y Task 5 en la Oleada 2 (después): quien ejecute Task 5 debe partir de `develop` ya con el cambio de Task 12 aplicado, y aplicar su propio cambio sobre el método `creditIfActive` sin tocar la firma ya modificada de `debitIfSufficientBalance`.
- `CompensationResultEvent` (Task 6) y el estado `COMPENSATING` de `TransactionStatus` no los consume ninguna otra tarea.

**4. Verificación manual previa a este plan:** los hallazgos #1, #2, #3, #4 fueron verificados leyendo el código real antes de escribirse (ver conversación previa a este plan). Los hallazgos #5-16 provienen directamente de los 2 informes de agentes Claude (uno de seguridad, uno de arquitectura), ambos ya de por sí basados en lectura de archivo:línea real citada en cada uno — no se re-verificó cada uno de ellos línea por línea en esta sesión de planificación, salvo el caso de `@Autowired` que sí se verificó y se descartó por impreciso.

## Ejecución

Una vez aprobado este plan, se ejecuta con `superpowers:subagent-driven-development`, oleada por oleada:

1. **Oleada 1** (Tasks 1, 2, 3, 7, 9, 10, 12, 13): 8 worktrees simultáneos, 8 sesiones OpenCode en paralelo (modelo según la tabla de delegación). Yo audito cada diff y corro los tests reales en su propio worktree antes de commitear. PRs abiertos y aprobados uno por uno (orden indistinto entre ellos), todos mergeados antes de arrancar la Oleada 2.
2. **Oleada 2** (Task 5, luego Task 6, en paralelo con Task 11 una vez 5 y 6 estén mergeadas): worktrees nuevos sobre el `develop` ya actualizado con la Oleada 1.
3. **Oleada 3** (Task 4, en paralelo con Task 8): Task 4 puede arrancar en cuanto Task 1 esté mergeada (no necesita esperar a las oleadas 2/3 completas); Task 8 arranca al final porque depende de que el topic `compensation-results` de Task 6 ya exista.

Cada tarea sigue el mismo patrón de auditoría que se usó en toda la feature `balance_ledger`: nunca se confía en el reporte del agente delegado, siempre se corren los tests reales (y en las tareas marcadas explícitamente, contra el stack real con `docker-compose up`) antes de dar por cerrada una tarea.
