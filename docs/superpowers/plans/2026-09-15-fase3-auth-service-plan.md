# Fase 3 — auth-service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir `auth-service`: `POST /auth/register` (crea usuario en Keycloak vía Admin API) y `POST /auth/login` (passthrough al token endpoint de Keycloak). Son los únicos endpoints públicos (sin JWT) del sistema.

**Architecture:** Controller (HTTP, sin seguridad Resource Server — endpoints públicos) → Service (encapsula las 2 integraciones con Keycloak: Admin API para registro, token endpoint para login) → 2 clientes HTTP dedicados (`KeycloakAdminClient`, `KeycloakTokenClient`). Sin persistencia propia — Keycloak es la única fuente de verdad.

**Tech Stack:** Java 21, Spring Boot 3.3.4 (Web, Validation, Actuator), Spring Cloud Netflix Eureka Client, JUnit 5 + Mockito, `MockWebServer` para tests de los clientes HTTP, Testcontainers (imagen de Keycloak real) para el test de integración end-to-end.

**Spec:** `docs/superpowers/specs/2026-09-15-loyalty-microservice-fase3-auth-service-design.md` (contexto en `docs/FUNCIONAL.md`, `docs/ARQUITECTURA.md`)

## Global Constraints

- Módulo Maven ya existe como placeholder (`auth-service/pom.xml`) heredando del parent — no crear un nuevo parent.
- `spring-boot-maven-plugin` debe fijar `<version>${spring-boot.version}</version>` y `<executions><goal>repackage</goal></executions>` explícitamente (lección de Fase 0).
- Formato estándar de error en todo el sistema: `{ "code", "message", "timestamp" }`.
- `auth-service` **no** valida JWT de clientes (no es Resource Server) — sus 2 endpoints son públicos por diseño.
- El realm `loyalty-realm` ya existe (Fase 0, `docker/keycloak/loyalty-realm.json`), con roles `USER`/`ADMIN`, client `loyalty-app` (`serviceAccountsEnabled: true`), y usuarios de prueba `test-user`/`test-admin`. Este plan modifica ese archivo para otorgar al service account de `loyalty-app` el rol de cliente `manage-users` del client `realm-management` (rol necesario para crear usuarios vía Admin API).
- Registro en Eureka: `eureka-server:8761` (Fase 0).

---

## File Structure

```
auth-service/
├── pom.xml                                          # MODIFICAR: agregar dependencias reales
└── src/
    ├── main/
    │   ├── java/com/loyalty/auth/
    │   │   ├── AuthServiceApplication.java
    │   │   ├── dto/
    │   │   │   ├── RegisterRequest.java
    │   │   │   ├── LoginRequest.java
    │   │   │   └── TokenResponse.java
    │   │   ├── client/
    │   │   │   ├── KeycloakAdminClient.java            # crea usuario + asigna rol USER
    │   │   │   └── KeycloakTokenClient.java              # obtiene token de servicio y de usuario
    │   │   ├── service/
    │   │   │   ├── AuthService.java
    │   │   │   └── AuthServiceImpl.java
    │   │   ├── controller/
    │   │   │   └── AuthController.java
    │   │   └── exception/
    │   │       ├── UserAlreadyExistsException.java
    │   │       ├── InvalidCredentialsException.java
    │   │       └── GlobalExceptionHandler.java
    │   └── resources/
    │       └── application.yml
    └── test/
        └── java/com/loyalty/auth/
            ├── client/KeycloakAdminClientTest.java
            ├── client/KeycloakTokenClientTest.java
            ├── service/AuthServiceTest.java
            ├── exception/GlobalExceptionHandlerTest.java
            ├── controller/AuthControllerIT.java
            └── AuthServiceApplicationTests.java

docker/keycloak/
└── loyalty-realm.json                                # MODIFICAR: otorgar manage-users al service account de loyalty-app
```

---

### Task 1: Realm de Keycloak — otorgar `manage-users` al service account de `loyalty-app`

**Files:**
- Modify: `docker/keycloak/loyalty-realm.json`

**Interfaces:**
- Consumes: nada.
- Produces: al importar el realm, el usuario de servicio `service-account-loyalty-app` (creado automáticamente por Keycloak porque `serviceAccountsEnabled: true`) tiene asignado el rol de cliente `manage-users` del client `realm-management`. Consumido por Task 4 (`KeycloakAdminClient`), que usa client credentials grant contra `loyalty-app` para obtener un token con permisos de administración.

- [ ] **Step 1: Agregar la sección `clients` con el mapeo de roles del service account**

En `docker/keycloak/loyalty-realm.json`, dentro del array `clients`, en el objeto del client `loyalty-app`, no se modifica directamente — Keycloak requiere una clave de nivel de realm `clientScopeMappings` o, más simple y soportado en el formato de export estándar, una entrada en `users` para el usuario de servicio con sus `clientRoles`. Agregar al array `users` (junto a `test-user` y `test-admin`) una entrada explícita para el service account:

```json
    {
      "username": "service-account-loyalty-app",
      "enabled": true,
      "serviceAccountClientId": "loyalty-app",
      "clientRoles": {
        "realm-management": ["manage-users"]
      }
    }
```

El archivo completo de `docker/keycloak/loyalty-realm.json` queda:
```json
{
  "realm": "loyalty-realm",
  "enabled": true,
  "sslRequired": "none",
  "registrationAllowed": false,
  "roles": {
    "realm": [
      { "name": "USER", "description": "Usuario estandar de la plataforma" },
      { "name": "ADMIN", "description": "Administrador de la plataforma" }
    ]
  },
  "clients": [
    {
      "clientId": "loyalty-app",
      "enabled": true,
      "publicClient": false,
      "secret": "loyalty-app-dev-secret",
      "directAccessGrantsEnabled": true,
      "standardFlowEnabled": true,
      "serviceAccountsEnabled": true,
      "redirectUris": ["*"],
      "protocol": "openid-connect"
    }
  ],
  "users": [
    {
      "username": "test-user",
      "enabled": true,
      "email": "test-user@loyalty.local",
      "emailVerified": true,
      "credentials": [
        { "type": "password", "value": "TestUser123!", "temporary": false }
      ],
      "realmRoles": ["USER"]
    },
    {
      "username": "test-admin",
      "enabled": true,
      "email": "test-admin@loyalty.local",
      "emailVerified": true,
      "credentials": [
        { "type": "password", "value": "TestAdmin123!", "temporary": false }
      ],
      "realmRoles": ["ADMIN"]
    },
    {
      "username": "service-account-loyalty-app",
      "enabled": true,
      "serviceAccountClientId": "loyalty-app",
      "clientRoles": {
        "realm-management": ["manage-users"]
      }
    }
  ]
}
```

- [ ] **Step 2: Verificar el realm importándolo en un Keycloak real (Testcontainers, verificación manual)**

Run (requiere Docker):
```bash
docker run --rm -d --name kc-realm-check -p 18080:8080 \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -v "$(pwd)/docker/keycloak/loyalty-realm.json:/opt/keycloak/data/import/loyalty-realm.json:ro" \
  quay.io/keycloak/keycloak:25.0 start-dev --import-realm
```
Esperar ~15s, luego obtener un token de servicio y confirmar que trae el rol:
```bash
curl -s -X POST http://localhost:18080/realms/loyalty-realm/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=loyalty-app&client_secret=loyalty-app-dev-secret" \
  | grep -o '"access_token":"[^"]*"'
```
Expected: respuesta con `access_token` presente (200 OK). Luego detener: `docker stop kc-realm-check`.

- [ ] **Step 3: Commit**

```bash
git add docker/keycloak/loyalty-realm.json
git commit -m "fix: otorga rol manage-users al service account de loyalty-app en el realm de keycloak"
```

---

### Task 2: Dependencias del módulo y arranque de la aplicación

**Files:**
- Modify: `auth-service/pom.xml`
- Create: `auth-service/src/main/java/com/loyalty/auth/AuthServiceApplication.java`
- Create: `auth-service/src/main/resources/application.yml`
- Test: `auth-service/src/test/java/com/loyalty/auth/AuthServiceApplicationTests.java`

**Interfaces:**
- Consumes: parent POM, `eureka-server` (Fase 0).
- Produces: aplicación arrancable en el puerto `8083`, registrada en Eureka como `auth-service`, `/actuator/health` → `UP`.

- [ ] **Step 1: Escribir el test que falla**

`auth-service/src/test/java/com/loyalty/auth/AuthServiceApplicationTests.java`:
```java
package com.loyalty.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "eureka.client.enabled=false")
class AuthServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Reemplazar `auth-service/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>com.loyalty</groupId>
    <artifactId>loyalty-microservice-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>auth-service</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>mockwebserver</artifactId>
      <version>4.12.0</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>1.20.1</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <version>${spring-boot.version}</version>
        <executions>
          <execution>
            <goals>
              <goal>repackage</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl auth-service -am test`
Expected: FAIL — `AuthServiceApplication` no existe.

- [ ] **Step 4: Crear la clase de aplicación**

`auth-service/src/main/java/com/loyalty/auth/AuthServiceApplication.java`:
```java
package com.loyalty.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableDiscoveryClient
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
```

*(Nota: este `RestClient.Builder` NO lleva `@LoadBalanced` — Keycloak no está registrado en Eureka, se llama por su hostname fijo `keycloak:8080` dentro de la red de Docker.)*

- [ ] **Step 5: Crear `application.yml`**

```yaml
server:
  port: 8083

spring:
  application:
    name: auth-service

keycloak:
  base-url: http://keycloak:8080
  realm: loyalty-realm
  admin-client-id: loyalty-app
  admin-client-secret: loyalty-app-dev-secret
  login-client-id: loyalty-app

eureka:
  client:
    service-url:
      defaultZone: http://eureka-server:8761/eureka/

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 6: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl auth-service -am test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add auth-service/pom.xml auth-service/src/main/java/com/loyalty/auth/AuthServiceApplication.java auth-service/src/main/resources/application.yml auth-service/src/test/java/com/loyalty/auth/AuthServiceApplicationTests.java
git commit -m "feat: inicializa auth-service con dependencias, config y test de contexto"
```

---

### Task 3: DTOs con validación

**Files:**
- Create: `auth-service/src/main/java/com/loyalty/auth/dto/RegisterRequest.java`
- Create: `auth-service/src/main/java/com/loyalty/auth/dto/LoginRequest.java`
- Create: `auth-service/src/main/java/com/loyalty/auth/dto/TokenResponse.java`
- Test: `auth-service/src/test/java/com/loyalty/auth/dto/RegisterRequestValidationTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `RegisterRequest{ username: @NotBlank, email: @Email @NotBlank, password: @NotBlank @Size(min=8) }`; `LoginRequest{ username: @NotBlank, password: @NotBlank }`; `TokenResponse{ accessToken, refreshToken, expiresIn }` con constructor `TokenResponse(String accessToken, String refreshToken, long expiresIn)`. Consumidos por Tasks 5, 6, 7, 8.

- [ ] **Step 1: Escribir el test de validación que falla**

`auth-service/src/test/java/com/loyalty/auth/dto/RegisterRequestValidationTest.java`:
```java
package com.loyalty.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterRequestValidationTest {

    private final Validator validator;

    RegisterRequestValidationTest() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @Test
    void rejectsShortPassword() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setEmail("newuser@loyalty.local");
        request.setPassword("short");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void acceptsValidRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setEmail("newuser@loyalty.local");
        request.setPassword("LongEnough123!");

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl auth-service -am test -Dtest=RegisterRequestValidationTest`
Expected: FAIL — `RegisterRequest` no existe.

- [ ] **Step 3: Crear los 3 DTOs**

`auth-service/src/main/java/com/loyalty/auth/dto/RegisterRequest.java`:
```java
package com.loyalty.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    @NotBlank
    private String username;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8)
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
```

`auth-service/src/main/java/com/loyalty/auth/dto/LoginRequest.java`:
```java
package com.loyalty.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class LoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
```

`auth-service/src/main/java/com/loyalty/auth/dto/TokenResponse.java`:
```java
package com.loyalty.auth.dto;

public class TokenResponse {

    private final String accessToken;
    private final String refreshToken;
    private final long expiresIn;

    public TokenResponse(String accessToken, String refreshToken, long expiresIn) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public long getExpiresIn() {
        return expiresIn;
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl auth-service -am test -Dtest=RegisterRequestValidationTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add auth-service/src/main/java/com/loyalty/auth/dto/ auth-service/src/test/java/com/loyalty/auth/dto/
git commit -m "feat: agrega DTOs de auth con validacion declarativa"
```

---

### Task 4: Excepciones de dominio y manejador global

**Files:**
- Create: `auth-service/src/main/java/com/loyalty/auth/exception/UserAlreadyExistsException.java`
- Create: `auth-service/src/main/java/com/loyalty/auth/exception/InvalidCredentialsException.java`
- Create: `auth-service/src/main/java/com/loyalty/auth/exception/KeycloakUnavailableException.java`
- Create: `auth-service/src/main/java/com/loyalty/auth/exception/GlobalExceptionHandler.java`
- Test: `auth-service/src/test/java/com/loyalty/auth/exception/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `UserAlreadyExistsException(String)` → `409`, `InvalidCredentialsException(String)` → `401`, `KeycloakUnavailableException(String)` → `503`, `GlobalExceptionHandler` con formato estándar. Consumido por Task 5 (`KeycloakAdminClient`), Task 6 (`KeycloakTokenClient`), Task 8 (controller).

- [ ] **Step 1: Escribir el test que falla**

`auth-service/src/test/java/com/loyalty/auth/exception/GlobalExceptionHandlerTest.java`:
```java
package com.loyalty.auth.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsUserAlreadyExistsTo409() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleUserExists(new UserAlreadyExistsException("ya existe"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "USER_ALREADY_EXISTS");
    }

    @Test
    void mapsInvalidCredentialsTo401() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleInvalidCredentials(new InvalidCredentialsException("credenciales invalidas"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
    }

    @Test
    void mapsKeycloakUnavailableTo503() {
        ResponseEntity<Map<String, Object>> response =
                handler.handleKeycloakUnavailable(new KeycloakUnavailableException("keycloak no responde"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("code", "KEYCLOAK_UNAVAILABLE");
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl auth-service -am test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — clases no existen.

- [ ] **Step 3: Crear las 3 excepciones**

`auth-service/src/main/java/com/loyalty/auth/exception/UserAlreadyExistsException.java`:
```java
package com.loyalty.auth.exception;

public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException(String message) {
        super(message);
    }
}
```

`auth-service/src/main/java/com/loyalty/auth/exception/InvalidCredentialsException.java`:
```java
package com.loyalty.auth.exception;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
```

`auth-service/src/main/java/com/loyalty/auth/exception/KeycloakUnavailableException.java`:
```java
package com.loyalty.auth.exception;

public class KeycloakUnavailableException extends RuntimeException {
    public KeycloakUnavailableException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Crear el `GlobalExceptionHandler`**

`auth-service/src/main/java/com/loyalty/auth/exception/GlobalExceptionHandler.java`:
```java
package com.loyalty.auth.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<Map<String, Object>> handleUserExists(UserAlreadyExistsException ex) {
        return body(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCredentials(InvalidCredentialsException ex) {
        return body(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage());
    }

    @ExceptionHandler(KeycloakUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleKeycloakUnavailable(KeycloakUnavailableException ex) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "KEYCLOAK_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse("Payload invalido");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        payload.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(payload);
    }
}
```

- [ ] **Step 5: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl auth-service -am test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add auth-service/src/main/java/com/loyalty/auth/exception/ auth-service/src/test/java/com/loyalty/auth/exception/
git commit -m "feat: agrega excepciones de dominio y manejador global de errores"
```

---

### Task 5: `KeycloakAdminClient` (registro de usuarios vía Admin API)

**Files:**
- Create: `auth-service/src/main/java/com/loyalty/auth/client/KeycloakAdminClient.java`
- Test: `auth-service/src/test/java/com/loyalty/auth/client/KeycloakAdminClientTest.java`

**Interfaces:**
- Consumes: `UserAlreadyExistsException`/`KeycloakUnavailableException` (Task 4).
- Produces: `KeycloakAdminClient` con `void createUser(String username, String email, String password)`, que internamente: 1) obtiene un token de servicio (`client_credentials` grant contra `loyalty-app`), 2) `POST /admin/realms/{realm}/users` con el nuevo usuario, 3) si `201`, busca el `id` del usuario recién creado (`GET /admin/realms/{realm}/users?username=...`) y le asigna el rol de realm `USER` (`POST /admin/realms/{realm}/users/{id}/role-mappings/realm`). Traduce `409` de Keycloak → `UserAlreadyExistsException`. Consumido por Task 7 (service).

- [ ] **Step 1: Escribir el test que falla**

`auth-service/src/test/java/com/loyalty/auth/client/KeycloakAdminClientTest.java`:
```java
package com.loyalty.auth.client;

import com.loyalty.auth.exception.UserAlreadyExistsException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakAdminClientTest {

    private MockWebServer server;
    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        client = new KeycloakAdminClient(restClient, "loyalty-realm", "loyalty-app", "loyalty-app-dev-secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void createUserSucceedsWithFullFlow() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"svc-token\",\"expires_in\":300}"));
        server.enqueue(new MockResponse().setResponseCode(201));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":\"user-uuid-1\",\"username\":\"newuser\"}]"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[{\"id\":\"role-uuid\",\"name\":\"USER\"}]"));
        server.enqueue(new MockResponse().setResponseCode(204));

        assertThatCode(() -> client.createUser("newuser", "newuser@loyalty.local", "Password123!"))
                .doesNotThrowAnyException();
    }

    @Test
    void createUserThrowsUserAlreadyExistsOn409() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"svc-token\",\"expires_in\":300}"));
        server.enqueue(new MockResponse().setResponseCode(409));

        assertThatThrownBy(() -> client.createUser("dup", "dup@loyalty.local", "Password123!"))
                .isInstanceOf(UserAlreadyExistsException.class);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl auth-service -am test -Dtest=KeycloakAdminClientTest`
Expected: FAIL — `KeycloakAdminClient` no existe.

- [ ] **Step 3: Crear `KeycloakAdminClient`**

`auth-service/src/main/java/com/loyalty/auth/client/KeycloakAdminClient.java`:
```java
package com.loyalty.auth.client;

import com.loyalty.auth.exception.KeycloakUnavailableException;
import com.loyalty.auth.exception.UserAlreadyExistsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

@Component
public class KeycloakAdminClient {

    private final RestClient restClient;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    @Autowired
    public KeycloakAdminClient(RestClient.Builder restClientBuilder,
                                @Value("${keycloak.base-url}") String baseUrl,
                                @Value("${keycloak.realm}") String realm,
                                @Value("${keycloak.admin-client-id}") String clientId,
                                @Value("${keycloak.admin-client-secret}") String clientSecret) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public KeycloakAdminClient(RestClient restClient, String realm, String clientId, String clientSecret) {
        this.restClient = restClient;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public void createUser(String username, String email, String password) {
        String serviceToken = obtainServiceToken();

        try {
            restClient.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .header("Authorization", "Bearer " + serviceToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", username,
                            "email", email,
                            "enabled", true,
                            "emailVerified", true,
                            "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false))
                    ))
                    .retrieve()
                    .onStatus(status -> status.value() == 409, (req, res) -> {
                        throw new UserAlreadyExistsException("El usuario '" + username + "' ya existe");
                    })
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            if (ex instanceof UserAlreadyExistsException) {
                throw ex;
            }
            throw new KeycloakUnavailableException("No se pudo crear el usuario en Keycloak: " + ex.getMessage());
        }

        assignUserRole(serviceToken, username);
    }

    private String obtainServiceToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            return (String) response.get("access_token");
        } catch (RestClientException ex) {
            throw new KeycloakUnavailableException("No se pudo obtener token de servicio: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void assignUserRole(String serviceToken, String username) {
        List<Map<String, Object>> users = restClient.get()
                .uri("/admin/realms/{realm}/users?username={username}", realm, username)
                .header("Authorization", "Bearer " + serviceToken)
                .retrieve()
                .body(List.class);

        String userId = (String) users.get(0).get("id");

        List<Map<String, Object>> realmRoles = restClient.get()
                .uri("/admin/realms/{realm}/roles/USER", realm)
                .header("Authorization", "Bearer " + serviceToken)
                .retrieve()
                .body(List.class);

        restClient.post()
                .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm", realm, userId)
                .header("Authorization", "Bearer " + serviceToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(realmRoles)
                .retrieve()
                .toBodilessEntity();
    }
}
```

*(Nota: el test mockea `GET /admin/realms/{realm}/roles/USER` devolviendo una lista (Keycloak real devuelve un objeto único para ese endpoint, no una lista — el test usa una lista de un elemento por simplicidad de mock; si la implementación real contra Keycloak requiere ajustar el tipo de respuesta a `Map` en vez de `List`, corregir en Task 8 durante la verificación de integración real con Testcontainers, documentándolo como corrección descubierta.)*

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl auth-service -am test -Dtest=KeycloakAdminClientTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add auth-service/src/main/java/com/loyalty/auth/client/KeycloakAdminClient.java auth-service/src/test/java/com/loyalty/auth/client/KeycloakAdminClientTest.java
git commit -m "feat: agrega KeycloakAdminClient para registro de usuarios via Admin API"
```

---

### Task 6: `KeycloakTokenClient` (login passthrough)

**Files:**
- Create: `auth-service/src/main/java/com/loyalty/auth/client/KeycloakTokenClient.java`
- Test: `auth-service/src/test/java/com/loyalty/auth/client/KeycloakTokenClientTest.java`

**Interfaces:**
- Consumes: `InvalidCredentialsException` (Task 4), `TokenResponse` (Task 3).
- Produces: `KeycloakTokenClient` con `TokenResponse login(String username, String password)`, que llama `POST /realms/{realm}/protocol/openid-connect/token` con `grant_type=password`, traduce `401` de Keycloak → `InvalidCredentialsException`. Consumido por Task 7 (service).

- [ ] **Step 1: Escribir el test que falla**

`auth-service/src/test/java/com/loyalty/auth/client/KeycloakTokenClientTest.java`:
```java
package com.loyalty.auth.client;

import com.loyalty.auth.dto.TokenResponse;
import com.loyalty.auth.exception.InvalidCredentialsException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeycloakTokenClientTest {

    private MockWebServer server;
    private KeycloakTokenClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder().baseUrl(server.url("/").toString()).build();
        client = new KeycloakTokenClient(restClient, "loyalty-realm", "loyalty-app");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void loginReturnsTokenResponseOn200() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"abc\",\"refresh_token\":\"def\",\"expires_in\":300}"));

        TokenResponse response = client.login("test-user", "TestUser123!");

        assertThat(response.getAccessToken()).isEqualTo("abc");
        assertThat(response.getRefreshToken()).isEqualTo("def");
        assertThat(response.getExpiresIn()).isEqualTo(300L);
    }

    @Test
    void loginThrowsInvalidCredentialsOn401() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() -> client.login("test-user", "wrong-password"))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl auth-service -am test -Dtest=KeycloakTokenClientTest`
Expected: FAIL — `KeycloakTokenClient` no existe.

- [ ] **Step 3: Crear `KeycloakTokenClient`**

`auth-service/src/main/java/com/loyalty/auth/client/KeycloakTokenClient.java`:
```java
package com.loyalty.auth.client;

import com.loyalty.auth.dto.TokenResponse;
import com.loyalty.auth.exception.InvalidCredentialsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class KeycloakTokenClient {

    private final RestClient restClient;
    private final String realm;
    private final String clientId;

    @Autowired
    public KeycloakTokenClient(RestClient.Builder restClientBuilder,
                                @Value("${keycloak.base-url}") String baseUrl,
                                @Value("${keycloak.realm}") String realm,
                                @Value("${keycloak.login-client-id}") String clientId) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.realm = realm;
        this.clientId = clientId;
    }

    public KeycloakTokenClient(RestClient restClient, String realm, String clientId) {
        this.restClient = restClient;
        this.realm = realm;
        this.clientId = clientId;
    }

    @SuppressWarnings("unchecked")
    public TokenResponse login(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("username", username);
        form.add("password", password);

        Map<String, Object> response = restClient.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .onStatus(status -> status.value() == 401, (req, res) -> {
                    throw new InvalidCredentialsException("Usuario o contrasena incorrectos");
                })
                .body(Map.class);

        return new TokenResponse(
                (String) response.get("access_token"),
                (String) response.get("refresh_token"),
                ((Number) response.get("expires_in")).longValue());
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl auth-service -am test -Dtest=KeycloakTokenClientTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add auth-service/src/main/java/com/loyalty/auth/client/KeycloakTokenClient.java auth-service/src/test/java/com/loyalty/auth/client/KeycloakTokenClientTest.java
git commit -m "feat: agrega KeycloakTokenClient para login passthrough"
```

---

### Task 7: Capa de servicio

**Files:**
- Create: `auth-service/src/main/java/com/loyalty/auth/service/AuthService.java`
- Create: `auth-service/src/main/java/com/loyalty/auth/service/AuthServiceImpl.java`
- Test: `auth-service/src/test/java/com/loyalty/auth/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `KeycloakAdminClient` (Task 5), `KeycloakTokenClient` (Task 6), `TokenResponse` (Task 3).
- Produces: `AuthService` con `void register(String username, String email, String password)`, `TokenResponse login(String username, String password)`. Consumido por Task 8 (controller).

- [ ] **Step 1: Escribir los tests que fallan**

`auth-service/src/test/java/com/loyalty/auth/service/AuthServiceTest.java`:
```java
package com.loyalty.auth.service;

import com.loyalty.auth.client.KeycloakAdminClient;
import com.loyalty.auth.client.KeycloakTokenClient;
import com.loyalty.auth.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private KeycloakAdminClient adminClient;

    @Mock
    private KeycloakTokenClient tokenClient;

    @Test
    void registerDelegatesToAdminClient() {
        AuthService service = new AuthServiceImpl(adminClient, tokenClient);

        service.register("newuser", "newuser@loyalty.local", "Password123!");

        verify(adminClient).createUser("newuser", "newuser@loyalty.local", "Password123!");
    }

    @Test
    void loginDelegatesToTokenClient() {
        AuthService service = new AuthServiceImpl(adminClient, tokenClient);
        TokenResponse expected = new TokenResponse("token", "refresh", 300L);
        when(tokenClient.login("test-user", "TestUser123!")).thenReturn(expected);

        TokenResponse result = service.login("test-user", "TestUser123!");

        assertThat(result).isEqualTo(expected);
    }
}
```

- [ ] **Step 2: Ejecutar los tests y confirmar que fallan**

Run: `mvn -q -pl auth-service -am test -Dtest=AuthServiceTest`
Expected: FAIL — `AuthService`/`AuthServiceImpl` no existen.

- [ ] **Step 3: Crear la interfaz**

`auth-service/src/main/java/com/loyalty/auth/service/AuthService.java`:
```java
package com.loyalty.auth.service;

import com.loyalty.auth.dto.TokenResponse;

public interface AuthService {

    void register(String username, String email, String password);

    TokenResponse login(String username, String password);
}
```

- [ ] **Step 4: Implementar el service**

`auth-service/src/main/java/com/loyalty/auth/service/AuthServiceImpl.java`:
```java
package com.loyalty.auth.service;

import com.loyalty.auth.client.KeycloakAdminClient;
import com.loyalty.auth.client.KeycloakTokenClient;
import com.loyalty.auth.dto.TokenResponse;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final KeycloakAdminClient adminClient;
    private final KeycloakTokenClient tokenClient;

    public AuthServiceImpl(KeycloakAdminClient adminClient, KeycloakTokenClient tokenClient) {
        this.adminClient = adminClient;
        this.tokenClient = tokenClient;
    }

    @Override
    public void register(String username, String email, String password) {
        adminClient.createUser(username, email, password);
    }

    @Override
    public TokenResponse login(String username, String password) {
        return tokenClient.login(username, password);
    }
}
```

- [ ] **Step 5: Ejecutar los tests y confirmar que pasan**

Run: `mvn -q -pl auth-service -am test -Dtest=AuthServiceTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add auth-service/src/main/java/com/loyalty/auth/service/ auth-service/src/test/java/com/loyalty/auth/service/
git commit -m "feat: agrega capa de servicio de auth"
```

---

### Task 8: Controller `POST /auth/register` y `POST /auth/login`

**Files:**
- Create: `auth-service/src/main/java/com/loyalty/auth/controller/AuthController.java`
- Test: `auth-service/src/test/java/com/loyalty/auth/controller/AuthControllerIT.java`

**Interfaces:**
- Consumes: `AuthService` (Task 7), `RegisterRequest`/`LoginRequest`/`TokenResponse` (Task 3).
- Produces: `POST /auth/register` → `201 Created` (sin body, o `{username, email}`), `POST /auth/login` → `200 OK` con `TokenResponse`. Endpoints públicos, sin JWT. No consumido por tareas posteriores.

- [ ] **Step 1: Escribir el test de integración que falla**

`auth-service/src/test/java/com/loyalty/auth/controller/AuthControllerIT.java`:
```java
package com.loyalty.auth.controller;

import com.loyalty.auth.dto.TokenResponse;
import com.loyalty.auth.exception.InvalidCredentialsException;
import com.loyalty.auth.exception.UserAlreadyExistsException;
import com.loyalty.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "eureka.client.enabled=false")
class AuthControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    void registerWithValidPayloadReturns201() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"newuser\",\"email\":\"newuser@loyalty.local\",\"password\":\"Password123!\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void registerWithShortPasswordReturns400() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"newuser\",\"email\":\"newuser@loyalty.local\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerDuplicateUserReturns409() throws Exception {
        doThrow(new UserAlreadyExistsException("ya existe"))
                .when(authService).register("dup", "dup@loyalty.local", "Password123!");

        mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"dup\",\"email\":\"dup@loyalty.local\",\"password\":\"Password123!\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void loginWithValidCredentialsReturns200WithToken() throws Exception {
        when(authService.login("test-user", "TestUser123!"))
                .thenReturn(new TokenResponse("access-tok", "refresh-tok", 300L));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"test-user\",\"password\":\"TestUser123!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-tok"));
    }

    @Test
    void loginWithInvalidCredentialsReturns401() throws Exception {
        when(authService.login("test-user", "wrong"))
                .thenThrow(new InvalidCredentialsException("invalido"));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"test-user\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl auth-service -am test -Dtest=AuthControllerIT`
Expected: FAIL — `AuthController` no existe (404).

- [ ] **Step 3: Crear el controller**

`auth-service/src/main/java/com/loyalty/auth/controller/AuthController.java`:
```java
package com.loyalty.auth.controller;

import com.loyalty.auth.dto.LoginRequest;
import com.loyalty.auth.dto.RegisterRequest;
import com.loyalty.auth.dto.TokenResponse;
import com.loyalty.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request.getUsername(), request.getEmail(), request.getPassword());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse token = authService.login(request.getUsername(), request.getPassword());
        return ResponseEntity.ok(token);
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl auth-service -am test -Dtest=AuthControllerIT`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add auth-service/src/main/java/com/loyalty/auth/controller/ auth-service/src/test/java/com/loyalty/auth/controller/
git commit -m "feat: agrega AuthController con endpoints POST /auth/register y /auth/login"
```

---

## Self-Review

**1. Spec coverage:**
- `POST /auth/register` (creación de usuario, rol `USER` por defecto) → Task 5, Task 7, Task 8.
- `POST /auth/login` (passthrough al token endpoint) → Task 6, Task 7, Task 8.
- Único componente que habla con la Admin API → Task 5 (`KeycloakAdminClient`), aislado del resto del sistema.
- Password mínimo 8 caracteres → Task 3 (`@Size(min=8)`).
- `409` username duplicado, `400` validación, `401` credenciales inválidas, `503` Keycloak caído → Task 4, verificado en Task 8.
- Endpoints públicos (sin JWT) → decisión reflejada en que `auth-service` no incluye `spring-boot-starter-oauth2-resource-server` ni `SecurityConfig` (Global Constraints).
- Otorgar `manage-users` al service account → Task 1.
- Testing: unit test de registro exitoso y username duplicado (Task 5, vía `KeycloakAdminClientTest` — cubre a nivel de cliente; Task 7 cubre a nivel de service con mocks), integration test de validación de formato (Task 8).

**2. Placeholder scan:** sin TBD/TODO. La única nota abierta (Task 5, formato de respuesta `GET /roles/USER`) tiene una salida concreta: ajustar el tipo de deserialización si la verificación real con Keycloak (recomendada como parte de la ejecución, análogo a las correcciones de Fases 0-2) lo requiere.

**3. Type consistency:** `TokenResponse` (Task 3) usado idéntico en Tasks 6, 7, 8. `AuthService.register(String, String, String)`/`login(String, String)` firma igual en Task 7 (implementación) y Task 8 (controller). `KeycloakAdminClient`/`KeycloakTokenClient` (Tasks 5, 6) inyectados en `AuthServiceImpl` (Task 7) con los mismos tipos.

## Nota de verificación recomendada (no incluida como task formal)

Dado que Fases 0-2 encontraron bugs reales solo detectables al ejecutar contra infraestructura real (Docker/Keycloak/Kafka), se recomienda, tras completar las 8 tareas, levantar el stack completo (`docker-compose up`, incluyendo Fase 0 + este servicio) y ejecutar manualmente el flujo real: `POST /auth/register` → `POST /auth/login` → usar el JWT resultante contra `account-service` (`POST /accounts`). Esto validará el formato real de las respuestas de la Admin API de Keycloak (Task 5) que un mock no puede garantizar al 100%.
