import cors from 'cors';
import express from 'express';
import fs from 'node:fs/promises';
import path from 'node:path';
import morgan from 'morgan';
import multer from 'multer';
import { fileURLToPath } from 'node:url';
import crypto from 'node:crypto';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const projectRoot = path.resolve(__dirname, '..');
const dataRoot = path.join(projectRoot, 'data');
const uploadsRoot = path.join(dataRoot, 'uploads');
const sessionsRoot = path.join(dataRoot, 'sessions');
const publicRoot = path.join(projectRoot, 'public');
const port = Number(process.env.PORT || 8080);
const requiredToken = process.env.API_TOKEN || '';

await ensureDir(dataRoot);
await ensureDir(uploadsRoot);
await ensureDir(sessionsRoot);

const app = express();
const upload = multer({ dest: uploadsRoot, limits: { fileSize: 25 * 1024 * 1024 } });

app.use(cors());
app.use(express.json({ limit: '20mb' }));
app.use(morgan('dev'));
app.use('/files', express.static(uploadsRoot));
app.use('/review', express.static(publicRoot));
app.use(requireAuth);

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'regula-capture-backend', now: new Date().toISOString() });
});

app.post('/api/v1/sessions', async (req, res, next) => {
  try {
    const sessionId = crypto.randomUUID();
    const session = {
      sessionId,
      tag: req.body?.tag || '',
      platform: req.body?.platform || 'unknown',
      source: req.body?.source || 'unknown',
      createdAt: new Date().toISOString(),
      images: [],
      result: null
    };
    await writeSession(sessionId, session);
    res.status(201).json({ sessionId });
  } catch (error) {
    next(error);
  }
});

app.post('/api/v1/sessions/:sessionId/images', upload.single('file'), async (req, res, next) => {
  try {
    const { sessionId } = req.params;
    const session = await readSession(sessionId);
    if (!req.file) {
      res.status(400).json({ error: 'file is required' });
      return;
    }

    const type = String(req.body?.type || 'unknown');
    const safeType = slugify(type);
    const extension = extensionFromMime(req.file.mimetype);
    const finalDir = path.join(uploadsRoot, sessionId);
    await ensureDir(finalDir);
    const finalName = `${Date.now()}-${safeType}${extension}`;
    const finalPath = path.join(finalDir, finalName);
    await fs.rename(req.file.path, finalPath);

    const fileBuffer = await fs.readFile(finalPath);
    const sha256 = crypto.createHash('sha256').update(fileBuffer).digest('hex');
    const imageRecord = {
      imageId: crypto.randomUUID(),
      type,
      mimeType: req.file.mimetype,
      originalName: req.file.originalname,
      fileName: finalName,
      storageKey: `${sessionId}/${finalName}`,
      absolutePath: finalPath,
      size: req.file.size,
      sha256,
      uploadedAt: new Date().toISOString(),
      url: `/files/${sessionId}/${finalName}`
    };

    session.images.push(imageRecord);
    await writeSession(sessionId, session);

    res.status(201).json({
      imageId: imageRecord.imageId,
      storageKey: imageRecord.storageKey,
      url: imageRecord.url,
      sha256: imageRecord.sha256
    });
  } catch (error) {
    next(error);
  }
});

app.post('/api/v1/sessions/:sessionId/results', async (req, res, next) => {
  try {
    const { sessionId } = req.params;
    const session = await readSession(sessionId);
    session.result = {
      receivedAt: new Date().toISOString(),
      payload: req.body,
      summary: summarizeResult(req.body)
    };
    await writeSession(sessionId, session);
    res.status(201).json({ ok: true, sessionId, stored: true, summary: session.result.summary });
  } catch (error) {
    next(error);
  }
});

app.get('/api/v1/sessions/:sessionId/summary', async (req, res, next) => {
  try {
    const session = await readSession(req.params.sessionId);
    res.json(buildSessionSummary(session));
  } catch (error) {
    next(error);
  }
});

app.get('/api/v1/sessions/:sessionId', async (req, res, next) => {
  try {
    const session = await readSession(req.params.sessionId);
    res.json(session);
  } catch (error) {
    next(error);
  }
});

app.get('/api/v1/sessions', async (_req, res, next) => {
  try {
    const files = await fs.readdir(sessionsRoot);
    const sessions = [];
    for (const file of files.filter((name) => name.endsWith('.json'))) {
      const raw = await fs.readFile(path.join(sessionsRoot, file), 'utf8');
      const parsed = JSON.parse(raw);
      sessions.push({
        sessionId: parsed.sessionId,
        tag: parsed.tag,
        createdAt: parsed.createdAt,
        imageCount: parsed.images.length,
        hasResult: Boolean(parsed.result),
        summary: parsed.result?.summary || summarizeResult(parsed.result?.payload)
      });
    }
    sessions.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    res.json({ sessions });
  } catch (error) {
    next(error);
  }
});

app.use((error, _req, res, _next) => {
  if (error?.code === 'ENOENT') {
    res.status(404).json({ error: 'session not found' });
    return;
  }
  if (error instanceof SyntaxError) {
    res.status(400).json({ error: 'invalid json body' });
    return;
  }
  console.error(error);
  res.status(500).json({ error: error?.message || 'internal error' });
});

app.listen(port, () => {
  console.log(`Regula backend listening on http://0.0.0.0:${port}`);
  if (requiredToken) {
    console.log('Authorization enabled via API_TOKEN');
  } else {
    console.log('Authorization disabled. Set API_TOKEN to require Bearer auth.');
  }
});

async function readSession(sessionId) {
  const sessionPath = path.join(sessionsRoot, `${sessionId}.json`);
  const raw = await fs.readFile(sessionPath, 'utf8');
  return JSON.parse(raw);
}

async function writeSession(sessionId, data) {
  const sessionPath = path.join(sessionsRoot, `${sessionId}.json`);
  await fs.writeFile(sessionPath, JSON.stringify(data, null, 2));
}

async function ensureDir(dirPath) {
  await fs.mkdir(dirPath, { recursive: true });
}

function requireAuth(req, res, next) {
  if (!requiredToken) {
    next();
    return;
  }

  const header = req.get('authorization') || '';
  const expected = `Bearer ${requiredToken}`;
  if (header !== expected) {
    res.status(401).json({ error: 'unauthorized' });
    return;
  }
  next();
}

function slugify(value) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '') || 'unknown';
}

function extensionFromMime(mimeType) {
  if (mimeType === 'image/png') return '.png';
  if (mimeType === 'image/webp') return '.webp';
  return '.jpg';
}

function buildSessionSummary(session) {
  return {
    sessionId: session.sessionId,
    tag: session.tag,
    platform: session.platform,
    source: session.source,
    createdAt: session.createdAt,
    imageCount: session.images?.length || 0,
    images: session.images || [],
    hasResult: Boolean(session.result),
    receivedAt: session.result?.receivedAt || null,
    summary: session.result?.summary || summarizeResult(session.result?.payload),
    payload: session.result?.payload || null
  };
}

function summarizeResult(payload) {
  if (!payload) {
    return {
      verdict: 'PENDING',
      label: 'Pendiente',
      explanation: 'La sesión todavía no tiene resultado del SDK.',
      status: null,
      failedChecks: [],
      warningChecks: []
    };
  }

  const status = payload.status || {};
  const failedChecks = [];
  const warningChecks = [];
  let hasInvalidInput = false;
  let hasTimeout = false;

  for (const check of payload.authenticityChecks || []) {
    const checkFailed = check.status === 0;
    const checkWarning = check.status === 2;
    for (const element of check.elements || []) {
      const record = {
        checkType: check.type,
        checkName: check.typeName,
        elementType: element.elementType,
        elementName: element.elementTypeName,
        status: element.status,
        diagnose: element.elementDiagnose,
        diagnoseName: element.elementDiagnoseName,
        pageIndex: check.pageIndex
      };
      if (element.status === 0 || checkFailed) failedChecks.push(record);
      if (element.status === 2 || checkWarning) warningChecks.push(record);
      const diagnose = String(element.elementDiagnoseName || '').toLowerCase();
      if (diagnose.includes('datos de entrada') || diagnose.includes('invalid input')) hasInvalidInput = true;
      if (diagnose.includes('tiempo') || diagnose.includes('timeout') || diagnose.includes('exceeded')) hasTimeout = true;
    }
  }

  const security = status.security;
  const overall = status.overall;
  const optical = status.optical;
  const imageQa = status.imageQa;
  const expiry = status.expiry;

  if (security === 1 && overall === 1 && optical === 1) {
    return {
      verdict: 'PASS',
      label: 'Autenticidad aprobada',
      explanation: 'El SDK reportó controles ópticos y de seguridad aprobados.',
      status,
      failedChecks,
      warningChecks
    };
  }

  if (hasInvalidInput || hasTimeout || imageQa === 0) {
    return {
      verdict: 'RECAPTURE',
      label: 'Requiere recaptura',
      explanation: 'El SDK falló por datos inválidos, timeout o calidad insuficiente. No es una conclusión limpia de documento falso.',
      status,
      failedChecks,
      warningChecks
    };
  }

  if (security === 0 || overall === 0 || optical === 0 || expiry === 0) {
    return {
      verdict: 'FAIL',
      label: 'No aprobado',
      explanation: 'El SDK reportó fallo en seguridad, validez óptica, expiración o resultado general.',
      status,
      failedChecks,
      warningChecks
    };
  }

  return {
    verdict: 'INCONCLUSIVE',
    label: 'No concluyente',
    explanation: 'No hay suficientes controles aprobados o fallidos para cerrar una decisión automática.',
    status,
    failedChecks,
    warningChecks
  };
}
