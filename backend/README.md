# Backend local de prueba

## Arranque

```bash
cd backend
npm install
npm start
```

Servidor por defecto:

- `http://127.0.0.1:8080`

## Autorizacion opcional

Si quieres probar el header `Authorization: Bearer ...` de la app:

```bash
cd backend
API_TOKEN=tu_token npm start
```

Luego en la app Android cargas ese mismo token en el campo `Bearer token API`.

## URL que debes poner en Android

- Emulador Android: `http://10.0.2.2:8080`
- Dispositivo fisico en la misma red: `http://IP_DE_TU_PC:8080`

## Endpoints disponibles

- `GET /health`
- `POST /api/v1/sessions`
- `POST /api/v1/sessions/:sessionId/images`
- `POST /api/v1/sessions/:sessionId/results`
- `GET /api/v1/sessions`
- `GET /api/v1/sessions/:sessionId`
- `GET /api/v1/sessions/:sessionId/summary`
- `GET /review/`

## Puesto fijo de revision

Abrir:

```text
http://127.0.0.1:8080/review/
```

En el servidor publico actual:

```text
http://216.238.105.109:18081/review/
```

El panel muestra sesiones, imagenes, tipo de documento, checks fallidos y veredicto POC.

## Veredictos

- `PASS`: controles de seguridad, optica y overall aprobados por el SDK.
- `FAIL`: fallo de seguridad, optica, expiracion o resultado general.
- `RECAPTURE`: timeout, datos invalidos o calidad insuficiente.
- `INCONCLUSIVE`: no hay suficientes controles para decision automatica.
- `PENDING`: sesion sin resultado.

## Persistencia

Todo se guarda en disco en:

- `backend/data/sessions`
- `backend/data/uploads`

## Prueba automatica

```bash
cd backend
npm run test:e2e
```
