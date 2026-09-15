Reto Técnico: Servicio de Transferencia de Puntos (Loyalty Microservice)
Contexto del Negocio
Estás construyendo un servicio para una plataforma de fidelización. Se requiere implementar un endpoint REST que permita a un usuario transferir puntos de su saldo a otra cuenta.

Requerimientos Funcionales
Endpoint REST:

POST /api/v1/points/transfer

Payload de entrada (JSON):

JSON
{
  "sourceAccountId": "acc-101",
  "targetAccountId": "acc-202",
  "amount": 150
}
Respuesta exitosa (200 OK o 201 Created): Retornar un identificador de transacción único (UUID), fecha/hora y los balances actualizados o el estado de la operación.

Reglas de Negocio & Validaciones:

El monto debe ser estrictamente mayor a 0 (usar validaciones declarativas jakarta.validation).

La cuenta origen y destino deben ser distintas.

La cuenta origen debe existir y tener saldo suficiente.

La cuenta destino debe existir y estar activa.

La operación debe ser atómica (si falla el crédito en destino, no debe persistirse el débito en origen).

Manejo de Excepciones y Errores HTTP:

400 Bad Request: Payload inválido o cuentas iguales.

404 Not Found: Alguna de las cuentas no existe.

422 Unprocessable Entity (o 409 Conflict): Saldo insuficiente o cuenta inactiva.

Manejador global de excepciones (@RestControllerAdvice) que estandarice la respuesta de error (código, mensaje, timestamp).

Requerimientos de Calidad & Arquitectura (Criterios EPAM/NEORIS)
Separación de Capas & SOLID:

Controller: Solo orquestación de petición/respuesta y validaciones de formato.

Service: Lógica de negocio pura (sin acoplamiento a HTTP).

Repository: Interfaz de acceso a datos (puedes usar Spring Data JPA o una implementación simulada en memoria/Mock).

Seguridad:

Define cómo asegurarías este endpoint con Spring Security (ej. exigir anotación @PreAuthorize("hasRole('USER')") o validar que el usuario autenticado por JWT coincida con sourceAccountId).

Testing:

Escribe 1 prueba unitaria de servicio (usando JUnit 5 + Mockito) que verifique el caso de error: Saldo insuficiente.

Escribe 1 prueba de integración de endpoint (usando MockMvc o @WebMvcTest) que verifique la validación de entrada cuando amount <= 0.

Estructura sugerida para tu entrega
DTOs / Request & Response: con anotaciones de validación (@NotNull, @Positive, etc.).

TransferService & TransferServiceImpl: Lógica de negocio con transaccionalidad (@Transactional).

GlobalExceptionHandler: Control de excepciones de negocio.

Pruebas (Tests): Implementación de la prueba unitaria y la prueba de integración.