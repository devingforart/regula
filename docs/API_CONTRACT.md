# API Contract

## 1. Crear sesion

`POST /api/v1/sessions`

Request:

```json
{
  "tag": "lead-123",
  "platform": "android",
  "source": "regula-mobile-sdk"
}
```

Response:

```json
{
  "sessionId": "a6d8fb7b-bae0-4e92-9e72-ef38f392fa9c"
}
```

## 2. Subir imagen

`POST /api/v1/sessions/{sessionId}/images`

Multipart fields:

- `type`: string
- `file`: binary jpeg

Response sugerida:

```json
{
  "imageId": "img_01",
  "storageKey": "sessions/a6d8/front.jpg",
  "url": "/files/a6d8/front.jpg",
  "sha256": "..."
}
```

## 3. Enviar resultado final

`POST /api/v1/sessions/{sessionId}/results`

Payload base:

```json
{
  "sessionId": "a6d8fb7b-bae0-4e92-9e72-ef38f392fa9c",
  "documentType": {
    "name": "Passport",
    "documentId": 123,
    "icaoCode": "ARG",
    "countryName": "Argentina"
  },
  "status": {
    "overall": 1,
    "optical": 1,
    "rfid": 0,
    "portrait": 1,
    "stopList": 0,
    "security": 1,
    "imageQa": 1,
    "expiry": 1,
    "captureIntegrity": 1
  },
  "textFields": [],
  "graphicFields": [],
  "authenticityChecks": [],
  "imageQualityChecks": [],
  "barcodes": [],
  "transactionInfo": {
    "tag": "lead-123",
    "transactionId": "regula-transaction-id"
  },
  "rawResult": "{}",
  "uploadedImages": []
}
```

Response:

```json
{
  "ok": true,
  "sessionId": "a6d8fb7b-bae0-4e92-9e72-ef38f392fa9c",
  "stored": true,
  "summary": {
    "verdict": "PASS",
    "label": "Autenticidad aprobada",
    "explanation": "El SDK reporto controles opticos y de seguridad aprobados."
  }
}
```

## 4. Listar sesiones

`GET /api/v1/sessions`

Devuelve sesiones ordenadas por fecha descendente, con cantidad de imagenes, flag de resultado y `summary` si existe.

## 5. Obtener detalle

`GET /api/v1/sessions/{sessionId}`

Devuelve la sesion completa: metadata, imagenes, resultado y payload original.

## 6. Obtener resumen de revision

`GET /api/v1/sessions/{sessionId}/summary`

Devuelve la sesion normalizada para el puesto fijo:

```json
{
  "sessionId": "a6d8fb7b-bae0-4e92-9e72-ef38f392fa9c",
  "imageCount": 3,
  "images": [],
  "summary": {
    "verdict": "RECAPTURE",
    "label": "Requiere recaptura",
    "failedChecks": [],
    "warningChecks": []
  },
  "payload": {}
}
```

## Puesto fijo web

`GET /review/`

Pantalla HTML liviana para revisar sesiones, imagenes, tipo de documento, estados SDK y checks fallidos.

## Semantica de veredicto POC

- `PASS`: seguridad, optica y overall aprobados por el SDK.
- `FAIL`: fallo de seguridad, optica, expiracion o resultado general.
- `RECAPTURE`: timeout, datos invalidos o calidad insuficiente; requiere repetir captura.
- `INCONCLUSIVE`: controles insuficientes para decision automatica.
- `PENDING`: sesion sin resultado.
- `DEVICE_NOT_CONFIRMED`: la app no confirmo inicializacion con autenticador Regula 7310; captura invalida para el POC de hardware.

## Recomendaciones backend

- Autenticar con `Authorization: Bearer <token>`.
- Guardar binarios en object storage.
- Guardar en BD solo metadata, hashes y referencias.
- Mantener `sessionId` tuyo separado de `transactionId` de Regula.
- Enmascarar PII si vas a replicar eventos o logs.
