# Fase 4 — Gateway

## Contexto

Quinta fase del sistema Loyalty Microservice Platform (ver `docs/FUNCIONAL.md` y `docs/ARQUITECTURA.md`). Depende de que `account-service` (Fase 1), `transfer-service` (Fase 2) y `auth-service` (Fase 3) estén registrados en Eureka (Fase 0).

Esta fase construye `gateway`: el único punto de entrada HTTP externo del sistema. Ningún cliente debe llamar directamente a los puertos internos de `account-service`, `transfer-service` o `auth-service` en un despliegue real (en Docker Compose local siguen expuestos por conveniencia de desarrollo, ver `ARQUITECTURA.md §Escalabilidad y despliegue`).

## Objetivo

`gateway` (Spring Cloud Gateway) enruta requests entrantes a los servicios correctos resolviendo sus instancias vía Eureka, sin necesidad de conocer URLs fijas.

## Rutas

| Path externo | Servicio destino (vía Eureka) |
|---|---|
| `/auth/**` | `auth-service` |
| `/accounts/**` | `account-service` |
| `/api/v1/points/**` | `transfer-service` |

Configuración vía `spring.cloud.gateway.routes` con `uri: lb://account-service` (etc.), usando el `service-id` con el que cada servicio se registra en Eureka.

## Responsabilidades adicionales del Gateway

- **CORS**: configuración centralizada aquí, no en cada servicio individual — evita duplicar la misma política 3 veces.
- **Rate limiting** (opcional para esta fase, documentado como extensión futura): Spring Cloud Gateway soporta `RequestRateLimiter` con Redis; no se incluye en el alcance actual salvo que se decida en el plan de implementación.
- **Logging de acceso**: un filtro global que loguea método, path y status de cada request que pasa por el gateway — punto único de observabilidad de tráfico externo.
- El Gateway **no** valida JWT (eso lo sigue haciendo cada Resource Server aguas abajo) — mantiene la responsabilidad de autenticación descentralizada en los servicios de negocio, evitando un único punto de fallo de autorización que además tendría que conocer los roles de cada ruta.

## Seguridad

- El Gateway no requiere su propia configuración de Resource Server; simplemente reenvía el header `Authorization` intacto al servicio destino.
- Se registra en Eureka como cualquier otro servicio, pero no es descubierto por nadie (nadie le enruta tráfico a él desde dentro del sistema).

## Testing

- Integration test (`@SpringBootTest` con `WebTestClient` + stubs de servicios registrados en un Eureka de prueba, o Testcontainers levantando una instancia mínima de cada servicio real) que verifica que cada ruta llega al servicio correcto.
- Test de CORS: preflight `OPTIONS` request responde con los headers esperados.

## Criterios de aceptación

1. Una request a `{gateway}/auth/register` llega a `auth-service`.
2. Una request a `{gateway}/accounts/{id}` llega a `account-service`, con el header `Authorization` reenviado intacto.
3. Una request a `{gateway}/api/v1/points/transfer` llega a `transfer-service`.
4. Si `account-service` tiene 2 instancias registradas en Eureka, el Gateway balancea entre ambas (verificable apagando una instancia y confirmando que el tráfico sigue funcionando contra la otra).

## Fuera de alcance de esta fase

- Rate limiting real (documentado como extensión futura).
- Autenticación/autorización centralizada en el Gateway — se mantiene descentralizada por diseño (ver Responsabilidades adicionales).
