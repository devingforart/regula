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
      payload: req.body
    };
    await writeSession(sessionId, session);
    res.status(201).json({ ok: true, sessionId, stored: true });
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
        hasResult: Boolean(parsed.result)
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
