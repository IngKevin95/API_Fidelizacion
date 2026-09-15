# Fase 3 — auth-service

## Contexto

Cuarta fase del sistema Loyalty Microservice Platform (ver `docs/FUNCIONAL.md` y `docs/ARQUITECTURA.md`). Depende de la Fase 0 (Keycloak con realm `loyalty-realm` importado y operativo). No depende funcionalmente de `account-service` ni `transfer-service` — puede desarrollarse en paralelo a las fases 1-2, aunque en el orden de entrega general va después.

Esta fase construye `auth-service`: la única pieza del sistema autorizada a hablar con la **Admin API de Keycloak**, exponiendo un endpoint simple de registro para que el resto del sistema (y los clientes finales) no necesiten conocer los detalles de esa integración.

## Objetivo

`auth-service` expone `POST /auth/register` que crea un usuario en Keycloak (rol `USER` por defecto) y `POST /auth/login` que actúa como passthrough al endpoint de token de Keycloak (Resource Owner Password Credentials grant, o Authorization Code si se decide en el plan de implementación).

## Alcance funcional (referencia `FUNCIONAL.md §3`)

| Endpoint | Método | Descripción |
|---|---|---|
| `/auth/register` | `POST` | Crea usuario en Keycloak vía Admin API. Body: `{ "username", "email", "password" }` |
| `/auth/login` | `POST` | Passthrough al token endpoint de Keycloak. Body: `{ "username", "password" }` → devuelve `{ "accessToken", "refreshToken", "expiresIn" }` |

## Capas (Controller / Service / Repository)

- **Controller** (`AuthController`): valida formato del body (`@Email`, `@NotBlank`, `@Size(min=8)` en password), delega al service.
- **Service** (`AuthService`): encapsula las llamadas a la Admin API de Keycloak (crear usuario, asignar rol `USER`) y al token endpoint (login). Usa un cliente HTTP (`RestClient`/`WebClient`) configurado con las credenciales de un client `admin-cli`-like con permisos de gestión de usuarios en el realm.
- **Repository**: no aplica — `auth-service` no tiene persistencia propia, Keycloak es la única fuente de verdad.

## Detalle de integración con Keycloak

- `auth-service` mantiene su propio token de servicio (client credentials grant contra un client Keycloak con rol `manage-users` en el realm `loyalty-realm`) para autenticar sus llamadas a la Admin API — distinto del client `loyalty-app` que usan los usuarios finales.
- **Registro**: `POST {keycloak-base}/admin/realms/loyalty-realm/users` con el payload del nuevo usuario, luego `PUT .../users/{id}/role-mappings/realm` para asignar `USER`.
- **Login**: `POST {keycloak-base}/realms/loyalty-realm/protocol/openid-connect/token` con `grant_type=password`, `client_id=loyalty-app`, credenciales del usuario.

## Reglas de negocio

- `username`/`email` deben ser únicos — Keycloak ya lo garantiza; `auth-service` traduce el `409 Conflict` de Keycloak a la respuesta estándar del sistema.
- Password mínimo 8 caracteres (validación declarativa antes de llegar a Keycloak, para fallar rápido).
- El usuario recién registrado recibe rol `USER` por defecto; asignar `ADMIN` **no** está expuesto por este endpoint (se hace manualmente vía consola de Keycloak — fuera de alcance de un flujo self-service).

## Seguridad

- `POST /auth/register` y `POST /auth/login` son los únicos endpoints públicos (sin JWT) de todo el sistema — son el punto de entrada antes de tener un token.
- `auth-service` nunca almacena contraseñas de usuarios finales — las reenvía directamente a Keycloak.

## Manejo de errores

- `GlobalExceptionHandler` estándar del sistema.
- `409 Conflict`: username/email ya existe en Keycloak.
- `400 Bad Request`: validación de formato fallida.
- `401 Unauthorized`: credenciales inválidas en login.
- `503 Service Unavailable`: Keycloak no responde (traducido desde el fallo de la llamada Admin API/token endpoint).

## Testing

- Unit test del `AuthService`: registro exitoso (mockeando el cliente HTTP a Keycloak), y el caso de username duplicado.
- Integration test (`@WebMvcTest`) de validación de formato en `/auth/register` (password corto, email inválido).
- Test de integración con Keycloak real (Testcontainers, imagen de Keycloak) para el flujo completo registro → login → token válido.

## Criterios de aceptación

1. `POST /auth/register` con datos válidos crea el usuario en Keycloak, verificable listándolo vía Admin API.
2. El usuario recién registrado puede hacer `POST /auth/login` y recibir un JWT válido.
3. Ese JWT es aceptado por `account-service`/`transfer-service` (Resource Server) para operaciones autorizadas.
4. Registro con username duplicado devuelve `409`.

## Fuera de alcance de esta fase

- Recuperación de contraseña, verificación de email, MFA (ver `FUNCIONAL.md §Fuera de alcance`).
- Asignación de rol `ADMIN` vía self-service.
- Refresh token flow completo (se documenta el campo en la respuesta de login, pero el endpoint de refresh puede quedar para una fase futura si se necesita).
