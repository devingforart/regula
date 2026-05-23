# Regula Android Capture App

Aplicacion Android en Kotlin para:

- inicializar `Regula Document Reader SDK`
- capturar un documento con la camara del SDK
- extraer resultados de OCR, autenticidad, image QA y metadatos
- guardar crops graficos localmente
- crear una sesion en tu backend
- subir imagenes y enviar el JSON final de resultados

## Estado actual

- La app Android compila en esta maquina.
- El backend local de prueba funciona y paso una prueba end-to-end.
- APK debug generado en `app/build/outputs/apk/debug/app-debug.apk`.

## Lo que falta para ejecutarla de verdad con Regula

1. Colocar `regula.license` en `app/src/main/assets/`.
2. Opcional: colocar `db.dat` en `app/src/main/assets/Regula/`.
3. Instalar el APK en emulador o dispositivo.
4. Configurar la URL real del backend en la app.

## Backend local

Ver [backend/README.md](/home/deving4art/Escritorio/dev/unum/regula/backend/README.md).

Resumen rapido:

```bash
cd backend
npm install
npm start
```

URL para la app:

- emulador Android: `http://10.0.2.2:8080`
- dispositivo fisico: `http://IP_DE_TU_PC:8080`

## Endpoints esperados

La app usa este contrato:

- `POST /api/v1/sessions`
- `POST /api/v1/sessions/{sessionId}/images`
- `POST /api/v1/sessions/{sessionId}/results`

Detalles en [docs/API_CONTRACT.md](/home/deving4art/Escritorio/dev/unum/regula/docs/API_CONTRACT.md).

## Notas de Regula

- Dependencias Android tomadas de la documentacion oficial:
  - https://docs.regulaforensics.com/develop/doc-reader-sdk/mobile/getting-started/installation/android/
- Inicializacion y licencia:
  - https://docs.regulaforensics.com/develop/doc-reader-sdk/mobile/getting-started/initialization/
- Captura y resultados:
  - https://docs.regulaforensics.com/develop/doc-reader-sdk/mobile/getting-started/document-processing/
  - https://docs.regulaforensics.com/develop/doc-reader-sdk/mobile/getting-started/results/android/
- Base de documentos:
  - https://docs.regulaforensics.com/develop/doc-reader-sdk/mobile/getting-started/database/

## Toolchain instalado aqui

- JDK 17 en `~/.local/opt/temurin-17`
- Android SDK en `~/Android/Sdk`
- Gradle 8.7 en `~/.local/opt/gradle-8.7`
- entorno shell en `~/.local/bin/regula-android-env`
