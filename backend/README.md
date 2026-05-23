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

## Persistencia

Todo se guarda en disco en:

- `backend/data/sessions`
- `backend/data/uploads`

## Prueba automatica

```bash
cd backend
npm run test:e2e
```
