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
  "url": "https://storage.example/sessions/a6d8/front.jpg"
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

## Recomendaciones backend

- Autenticar con `Authorization: Bearer <token>`.
- Guardar binarios en object storage.
- Guardar en BD solo metadata, hashes y referencias.
- Mantener `sessionId` tuyo separado de `transactionId` de Regula.
- Enmascarar PII si vas a replicar eventos o logs.
