# Regula 7310 POC E2E

POC Android + backend para capturar documentos con Regula Document Reader SDK, enviar imagenes/resultados al servidor y revisar el caso en un puesto fijo web.

## Arquitectura

- Android app Kotlin: inicializa Regula SDK, ejecuta `FullAuth`, extrae OCR, imagenes, autenticidad, image QA y estados.
- Backend Node/Express: recibe sesiones, imagenes `multipart/form-data` y resultado JSON.
- Puesto fijo web: `GET /review/` lista capturas, muestra imagenes, checks fallidos y veredicto POC.
- Servidor actual: `http://216.238.105.109:18081`.

## Estado

- La app compila en esta maquina.
- La app apunta por defecto al backend `http://216.238.105.109:18081`.
- El backend calcula un `summary` operativo: `PASS`, `FAIL`, `RECAPTURE`, `INCONCLUSIVE` o `PENDING`.
- APK debug: `app/build/outputs/apk/debug/app-debug.apk`.

## Flujo Android

1. Instalar la APK en el equipo Android/Regula.
2. Abrir la app.
3. Inicializar Regula. La app intenta BLE oficial, BLE manual y fallback local con `regula.license`.
4. Capturar el documento fisico con el escenario `FullAuth`.
5. La app crea una sesion, sube imagenes y envia el JSON del SDK al backend.
6. En pantalla muestra `veredicto POC`, `sessionId`, estado general, seguridad e image QA.

## Revision

Abrir:

```text
http://216.238.105.109:18081/review/
```

Endpoints utiles:

```bash
curl -s http://216.238.105.109:18081/api/v1/sessions | jq
curl -s http://216.238.105.109:18081/api/v1/sessions/SESSION_ID/summary | jq
```

## Criterio POC

- `PASS`: seguridad, optica y overall aprobados por el SDK.
- `FAIL`: fallo de seguridad, optica, expiracion o resultado general.
- `RECAPTURE`: hubo timeout, datos de entrada invalidos o calidad insuficiente; no es una conclusion limpia de documento falso.
- `INCONCLUSIVE`: no hay suficientes controles ejecutados para decidir automaticamente.
- `PENDING`: sesion sin resultado.

## Backend local

```bash
cd backend
npm install
npm start
```

Prueba automatica:

```bash
cd backend
npm run test:e2e
```

Contrato: [docs/API_CONTRACT.md](/home/deving4art/Escritorio/dev/unum/regula/docs/API_CONTRACT.md).

## Toolchain

- JDK 17 en `~/.local/opt/temurin-17`
- Android SDK en `~/Android/Sdk`
- entorno shell en `~/.local/bin/regula-android-env`

Compilar APK:

```bash
source ~/.local/bin/regula-android-env
./gradlew assembleDebug
```
