# Fase 4 — gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Construir `gateway`: punto de entrada HTTP único que enruta `/auth/**` → `auth-service`, `/accounts/**` → `account-service`, `/api/v1/points/**` → `transfer-service`, resolviendo instancias vía Eureka, con CORS centralizado y logging de acceso.

**Architecture:** Spring Cloud Gateway (reactive, WebFlux) — no comparte stack con los demás servicios (Spring MVC/servlet). Rutas declaradas en `application.yml` con `uri: lb://<service-id>`. Un `GlobalFilter` propio agrega logging de acceso. No valida JWT (decisión deliberada — cada servicio de negocio sigue siendo su propio Resource Server).

**Tech Stack:** Java 21, Spring Boot 3.3.4, Spring Cloud Gateway (WebFlux), Spring Cloud Netflix Eureka Client, JUnit 5, WireMock (para stubbear los servicios destino en los tests de integración, evitando depender de Eureka real en tests).

**Spec:** `docs/superpowers/specs/2026-09-15-loyalty-microservice-fase4-gateway-design.md` (contexto en `docs/FUNCIONAL.md`, `docs/ARQUITECTURA.md`)

## Global Constraints

- Módulo Maven ya existe como placeholder (`gateway/pom.xml`) heredando del parent — no crear un nuevo parent.
- `spring-boot-maven-plugin` debe fijar `<version>${spring-boot.version}</version>` y `<executions><goal>repackage</goal></executions>` explícitamente (lección de Fase 0).
- Spring Cloud Gateway es **reactivo** (WebFlux) — no mezclar con `spring-boot-starter-web` (servlet), que causaría conflicto de contenedor embebido. Usar `spring-cloud-starter-gateway` solo.
- El Gateway **no** valida JWT — reenvía el header `Authorization` intacto; cada servicio de negocio sigue siendo su propio Resource Server (decisión de diseño de la spec, no una omisión).
- Registro en Eureka: `eureka-server:8761` (Fase 0). Servicios ya registrados con estos `service-id` (nombre de `spring.application.name` en cada uno): `account-service`, `transfer-service`, `auth-service`.
- Como en tests no hay un Eureka real disponible por defecto, las rutas se prueban apuntando a servidores WireMock estáticos vía sobreescritura de propiedades (`spring.cloud.gateway.routes[N].uri`), no vía `lb://`.

---

## File Structure

```
gateway/
├── pom.xml                                          # MODIFICAR: agregar dependencias reales
└── src/
    ├── main/
    │   ├── java/com/loyalty/gateway/
    │   │   ├── GatewayApplication.java
    │   │   ├── config/
    │   │   │   └── CorsConfig.java                     # WebFluxConfigurer / CorsWebFilter
    │   │   └── filter/
    │   │       └── AccessLoggingFilter.java              # GlobalFilter + Ordered
    │   └── resources/
    │       └── application.yml                          # rutas + CORS + eureka
    └── test/
        └── java/com/loyalty/gateway/
            ├── GatewayApplicationTests.java
            └── RoutingIT.java
```

---

### Task 1: Dependencias del módulo y arranque de la aplicación

**Files:**
- Modify: `gateway/pom.xml`
- Create: `gateway/src/main/java/com/loyalty/gateway/GatewayApplication.java`
- Create: `gateway/src/main/resources/application.yml`
- Test: `gateway/src/test/java/com/loyalty/gateway/GatewayApplicationTests.java`

**Interfaces:**
- Consumes: parent POM, `eureka-server` (Fase 0).
- Produces: aplicación reactiva arrancable en el puerto `8080`, registrada en Eureka como `gateway`, `/actuator/health` → `UP`.

- [ ] **Step 1: Escribir el test que falla**

`gateway/src/test/java/com/loyalty/gateway/GatewayApplicationTests.java`:
```java
package com.loyalty.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "eureka.client.enabled=false")
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Reemplazar `gateway/pom.xml`**

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

  <artifactId>gateway</artifactId>
  <packaging>jar</packaging>

  <dependencies>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-gateway</artifactId>
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
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-starter-loadbalancer</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>io.projectreactor</groupId>
      <artifactId>reactor-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.github.tomakehurst</groupId>
      <artifactId>wiremock-jre8</artifactId>
      <version>2.35.2</version>
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

*(Nota: `spring-cloud-starter-gateway` trae su propio servidor Netty reactivo — no incluir `spring-boot-starter-web` en este módulo, causaría conflicto de auto-configuración de servidor embebido servlet vs. reactivo.)*

- [ ] **Step 3: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl gateway -am test`
Expected: FAIL — `GatewayApplication` no existe.

- [ ] **Step 4: Crear la clase de aplicación**

`gateway/src/main/java/com/loyalty/gateway/GatewayApplication.java`:
```java
package com.loyalty.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [ ] **Step 5: Crear `application.yml`**

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: lb://auth-service
          predicates:
            - Path=/auth/**
        - id: account-service
          uri: lb://account-service
          predicates:
            - Path=/accounts/**
        - id: transfer-service
          uri: lb://transfer-service
          predicates:
            - Path=/api/v1/points/**

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

Run: `mvn -q -pl gateway -am test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add gateway/pom.xml gateway/src/main/java/com/loyalty/gateway/GatewayApplication.java gateway/src/main/resources/application.yml gateway/src/test/java/com/loyalty/gateway/GatewayApplicationTests.java
git commit -m "feat: inicializa gateway con dependencias, rutas base y test de contexto"
```

---

### Task 2: CORS centralizado

**Files:**
- Create: `gateway/src/main/java/com/loyalty/gateway/config/CorsConfig.java`
- Test: `gateway/src/test/java/com/loyalty/gateway/config/CorsConfigTest.java`

**Interfaces:**
- Consumes: nada nuevo.
- Produces: bean `CorsWebFilter` que permite cualquier origen, métodos `GET,POST,PATCH,PUT,DELETE,OPTIONS`, y todos los headers — política única para todo el sistema, en vez de repetirla en cada servicio.

- [ ] **Step 1: Escribir el test que falla**

`gateway/src/test/java/com/loyalty/gateway/config/CorsConfigTest.java`:
```java
package com.loyalty.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {

    @Test
    void createsCorsWebFilterAllowingAnyOriginAndCommonMethods() {
        CorsConfig config = new CorsConfig();

        CorsWebFilter filter = config.corsWebFilter();

        assertThat(filter).isNotNull();
    }

    @Test
    void configurationAllowsExpectedMethods() {
        CorsConfig config = new CorsConfig();
        CorsConfiguration corsConfiguration = config.buildCorsConfiguration();

        assertThat(corsConfiguration.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");
        assertThat(corsConfiguration.getAllowedOriginPatterns()).contains("*");
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl gateway -am test -Dtest=CorsConfigTest`
Expected: FAIL — `CorsConfig` no existe.

- [ ] **Step 3: Crear `CorsConfig`**

`gateway/src/main/java/com/loyalty/gateway/config/CorsConfig.java`:
```java
package com.loyalty.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class CorsConfig {

    public CorsConfiguration buildCorsConfiguration() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
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

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl gateway -am test -Dtest=CorsConfigTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add gateway/src/main/java/com/loyalty/gateway/config/ gateway/src/test/java/com/loyalty/gateway/config/
git commit -m "feat: agrega CORS centralizado en el gateway"
```

---

### Task 3: Filtro global de logging de acceso

**Files:**
- Create: `gateway/src/main/java/com/loyalty/gateway/filter/AccessLoggingFilter.java`
- Test: `gateway/src/test/java/com/loyalty/gateway/filter/AccessLoggingFilterTest.java`

**Interfaces:**
- Consumes: nada nuevo.
- Produces: `AccessLoggingFilter implements GlobalFilter, Ordered` que loguea método, path y status de cada request que pasa por el Gateway. No consumido por tareas posteriores — se registra automáticamente como bean `GlobalFilter` en la cadena de Spring Cloud Gateway.

- [ ] **Step 1: Escribir el test que falla**

`gateway/src/test/java/com/loyalty/gateway/filter/AccessLoggingFilterTest.java`:
```java
package com.loyalty.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessLoggingFilterTest {

    private final AccessLoggingFilter filter = new AccessLoggingFilter();

    @Test
    void hasLowestPrecedenceOrder() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void delegatesToFilterChainAndCompletes() {
        ServerHttpRequest request = MockServerHttpRequest.get("/accounts/acc-1").build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        Mono<Void> result = filter.filter(exchange, chain);

        assertThat(result.blockOptional()).isEmpty();
        verify(chain).filter(exchange);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl gateway -am test -Dtest=AccessLoggingFilterTest`
Expected: FAIL — `AccessLoggingFilter` no existe.

- [ ] **Step 3: Crear `AccessLoggingFilter`**

`gateway/src/main/java/com/loyalty/gateway/filter/AccessLoggingFilter.java`:
```java
package com.loyalty.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AccessLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();

        return chain.filter(exchange).doFinally(signal -> {
            int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 0;
            log.info("{} {} -> {}", method, path, status);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
```

- [ ] **Step 4: Ejecutar el test y confirmar que pasa**

Run: `mvn -q -pl gateway -am test -Dtest=AccessLoggingFilterTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add gateway/src/main/java/com/loyalty/gateway/filter/ gateway/src/test/java/com/loyalty/gateway/filter/
git commit -m "feat: agrega filtro global de logging de acceso"
```

---

### Task 4: Verificación de enrutamiento (integración con WireMock)

**Files:**
- Test: `gateway/src/test/java/com/loyalty/gateway/RoutingIT.java`

**Interfaces:**
- Consumes: la configuración de rutas de `application.yml` (Task 1), sobreescrita en este test para apuntar a servidores WireMock en vez de `lb://`.
- Produces: nada consumido por otras tareas — es la verificación final de que las 3 rutas despachan al backend correcto y reenvían el header `Authorization`.

- [ ] **Step 1: Escribir el test de integración**

`gateway/src/test/java/com/loyalty/gateway/RoutingIT.java`:
```java
package com.loyalty.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.reactive.server.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = "eureka.client.enabled=false")
class RoutingIT {

    private static final WireMockServer authServiceMock = new WireMockServer(0);
    private static final WireMockServer accountServiceMock = new WireMockServer(0);
    private static final WireMockServer transferServiceMock = new WireMockServer(0);

    @BeforeAll
    static void startMocks() {
        authServiceMock.start();
        accountServiceMock.start();
        transferServiceMock.start();
    }

    @AfterAll
    static void stopMocks() {
        authServiceMock.stop();
        accountServiceMock.stop();
        transferServiceMock.stop();
    }

    @AfterEach
    void resetMocks() {
        authServiceMock.resetAll();
        accountServiceMock.resetAll();
        transferServiceMock.resetAll();
    }

    @DynamicPropertySource
    static void overrideRoutes(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gateway.routes[0].id", () -> "auth-service");
        registry.add("spring.cloud.gateway.routes[0].uri", () -> "http://localhost:" + authServiceMock.port());
        registry.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/auth/**");

        registry.add("spring.cloud.gateway.routes[1].id", () -> "account-service");
        registry.add("spring.cloud.gateway.routes[1].uri", () -> "http://localhost:" + accountServiceMock.port());
        registry.add("spring.cloud.gateway.routes[1].predicates[0]", () -> "Path=/accounts/**");

        registry.add("spring.cloud.gateway.routes[2].id", () -> "transfer-service");
        registry.add("spring.cloud.gateway.routes[2].uri", () -> "http://localhost:" + transferServiceMock.port());
        registry.add("spring.cloud.gateway.routes[2].predicates[0]", () -> "Path=/api/v1/points/**");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void routesAuthRequestsToAuthService() {
        authServiceMock.stubFor(post(urlEqualTo("/auth/register"))
                .willReturn(aResponse().withStatus(201)));

        webTestClient.post().uri("/auth/register")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void routesAccountRequestsToAccountServiceAndForwardsAuthorizationHeader() {
        accountServiceMock.stubFor(get(urlEqualTo("/accounts/acc-1"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        webTestClient.get().uri("/accounts/acc-1")
                .header("Authorization", "Bearer test-token")
                .exchange()
                .expectStatus().isOk();

        accountServiceMock.verify(getRequestedFor(urlEqualTo("/accounts/acc-1"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void routesTransferRequestsToTransferService() {
        transferServiceMock.stubFor(post(urlEqualTo("/api/v1/points/transfer"))
                .willReturn(aResponse().withStatus(202)));

        webTestClient.post().uri("/api/v1/points/transfer")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(202);
    }
}
```

- [ ] **Step 2: Ejecutar el test y confirmar que falla**

Run: `mvn -q -pl gateway -am test -Dtest=RoutingIT`
Expected: FAIL o error de configuración — las rutas de `application.yml` (Task 1) usan `lb://`, no las URIs dinámicas de WireMock; la sobreescritura vía `@DynamicPropertySource` con índices de array (`routes[0]`, `routes[1]`, `routes[2]`) debe machear exactamente el número y orden de rutas ya declaradas para que Spring las reemplace correctamente. Si el test falla porque las rutas base y las de test coexisten duplicadas, es esperado en este primer run — se corrige en el siguiente step si aplica.

- [ ] **Step 3: Ejecutar el test y verificar el resultado real; ajustar si es necesario**

Run: `mvn -q -pl gateway -am test -Dtest=RoutingIT`

Si falla porque Spring Cloud Gateway no permite sobreescribir `uri` de una ruta ya definida por índice via propiedades dinámicas de test (comportamiento común: las properties de `application.yml` y las de `@DynamicPropertySource` a veces no fusionan por índice de array de la forma esperada), la corrección es **no** declarar rutas en `gateway/src/main/resources/application.yml` como parte de este test, sino verificar que el `RouteLocator` autoconfigurado lee correctamente routes cuando el `application.yml` de test (`gateway/src/test/resources/application.yml`) reemplaza por completo `spring.cloud.gateway.routes` apuntando a los puertos fijos de WireMock — para esto, iniciar WireMock en puertos fijos conocidos de antemano (ej. `18091`, `18092`, `18093`) en vez de puertos aleatorios, y declarar un `application.yml` de test que apunte a esos puertos fijos, documentando esta corrección en el plan.

Expected tras el ajuste: PASS (3 tests) — verifica el resultado real y documenta en el plan cualquier ajuste que haya sido necesario, siguiendo la disciplina de Fases 0-3 de no dar una tarea por completa sin ejecutar contra el comportamiento real de Spring Cloud Gateway.

- [ ] **Step 4: Commit**

```bash
git add gateway/src/test/java/com/loyalty/gateway/RoutingIT.java
git commit -m "test: verifica enrutamiento del gateway con WireMock"
```

---

## Self-Review

**1. Spec coverage:**
- Las 3 rutas (`/auth/**`, `/accounts/**`, `/api/v1/points/**`) → Task 1 (config) + Task 4 (verificación).
- CORS centralizado → Task 2.
- Logging de acceso → Task 3.
- Gateway no valida JWT, reenvía `Authorization` intacto → Task 4 verifica explícitamente el reenvío del header; el Gateway no incluye ninguna dependencia de seguridad OAuth2 (decisión reflejada en Global Constraints y en el `pom.xml` de Task 1, que no incluye `spring-boot-starter-oauth2-resource-server`).
- Registro en Eureka → Task 1 (`@EnableDiscoveryClient` + `eureka.client.service-url`).
- Criterios de aceptación de la spec (cada ruta llega al servicio correcto, header reenviado, balanceo entre instancias) → Task 4 cubre los primeros dos; el balanceo entre 2 instancias de un mismo servicio requeriría un entorno con Eureka real y 2 instancias corriendo (Docker Compose), fuera del alcance de un test unitario/de integración — se recomienda verificar manualmente al levantar el stack completo, análogo a la nota de verificación de Fase 3.
- Rate limiting: explícitamente fuera de alcance (documentado en la spec), no incluido en este plan.

**2. Placeholder scan:** sin TBD/TODO. Task 4 incluye una contingencia explícita y accionable (no un placeholder) para el caso de que la sobreescritura de rutas por `@DynamicPropertySource` con índices no funcione como se espera con Spring Cloud Gateway — con una alternativa concreta (application.yml de test con puertos fijos).

**3. Type consistency:** `AccessLoggingFilter` implementa `GlobalFilter, Ordered` consistentemente entre Task 3 (implementación) y su test. Las rutas declaradas en Task 1 (`auth-service`, `account-service`, `transfer-service` como IDs) se referencian con los mismos IDs en la sobreescritura de Task 4.

## Nota de verificación recomendada (no incluida como task formal)

Análogo a Fases 0-3: se recomienda, tras completar las 4 tareas, levantar el stack completo (`docker-compose up`, agregando `gateway` al compose si aún no está — ver nota de Fase 3 sobre `auth-service` tampoco estar en el compose) y probar manualmente que una request real a `{gateway}/accounts/{id}` con un JWT de Keycloak real llega correctamente a `account-service` a través de Eureka real.

## Correcciones descubiertas durante la ejecución

Al ejecutar Task 4 se descubrió que **WireMock (`wiremock-jre8`) es incompatible con un módulo puramente reactivo**: su servidor embebido usa Jetty 9 sobre `javax.servlet`, que no existe en el classpath de un proyecto WebFlux-only (sin `spring-boot-starter-web`), y agregar `javax.servlet-api` manualmente solo destapaba la siguiente clase faltante de Jetty (`org.eclipse.jetty.util.log.Log`) — un problema de incompatibilidad de stack, no de una dependencia faltante puntual.

**Fix:** se reemplazó WireMock por servidores stub construidos directamente con `reactor-netty` (`reactor.netty.http.server.HttpServer`), que ya es una dependencia transitiva de `spring-cloud-starter-gateway` — cero dependencias nuevas, cero conflictos de stack servlet/reactivo. `RoutingIT` (Task 4) queda así:

```java
package com.loyalty.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "eureka.client.enabled=false")
class RoutingIT {

    private static DisposableServer authServiceMock;
    private static DisposableServer accountServiceMock;
    private static DisposableServer transferServiceMock;

    private static final AtomicReference<String> lastAccountsAuthHeader = new AtomicReference<>();

    @BeforeAll
    static void startMocks() {
        authServiceMock = HttpServer.create()
                .port(18191)
                .route(routes -> routes.post("/auth/register",
                        (req, res) -> res.status(201).send()))
                .bindNow();

        accountServiceMock = HttpServer.create()
                .port(18192)
                .route(routes -> routes.get("/accounts/acc-1", (req, res) -> {
                    lastAccountsAuthHeader.set(req.requestHeaders().get("Authorization"));
                    return res.status(200).sendString(Mono.just("{}"));
                }))
                .bindNow();

        transferServiceMock = HttpServer.create()
                .port(18193)
                .route(routes -> routes.post("/api/v1/points/transfer",
                        (req, res) -> res.status(202).send()))
                .bindNow();
    }

    @AfterAll
    static void stopMocks() {
        authServiceMock.disposeNow();
        accountServiceMock.disposeNow();
        transferServiceMock.disposeNow();
    }

    @AfterEach
    void resetState() {
        lastAccountsAuthHeader.set(null);
    }

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void routesAuthRequestsToAuthService() {
        webTestClient().post().uri("/auth/register")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isCreated();
    }

    @Test
    void routesAccountRequestsToAccountServiceAndForwardsAuthorizationHeader() {
        webTestClient().get().uri("/accounts/acc-1")
                .header("Authorization", "Bearer test-token")
                .exchange()
                .expectStatus().isOk();

        assertThat(lastAccountsAuthHeader.get()).isEqualTo("Bearer test-token");
    }

    @Test
    void routesTransferRequestsToTransferService() {
        webTestClient().post().uri("/api/v1/points/transfer")
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isEqualTo(202);
    }
}
```

Las rutas se sobreescriben con un `gateway/src/test/resources/application.yml` que apunta directamente a los puertos fijos `18191`/`18192`/`18193` en vez de `lb://` (evitando también la incertidumbre original sobre `@DynamicPropertySource` con índices de array). `wiremock-jre8` se eliminó del `pom.xml` (nunca se agregó `javax.servlet-api`, quedó descartado). Los 3 tests pasan, confirmando que cada ruta llega al backend correcto y que el header `Authorization` se reenvía intacto.
