const baseUrl = process.env.BASE_URL || 'http://127.0.0.1:8080';
const token = process.env.API_TOKEN || '';

const authHeaders = token ? { Authorization: `Bearer ${token}` } : {};

const sessionResponse = await fetch(`${baseUrl}/api/v1/sessions`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    ...authHeaders
  },
  body: JSON.stringify({ tag: 'smoke-test', platform: 'node', source: 'script' })
});

if (!sessionResponse.ok) {
  throw new Error(`Failed creating session: ${sessionResponse.status} ${await sessionResponse.text()}`);
}

const { sessionId } = await sessionResponse.json();
const form = new FormData();
form.append('type', 'front-original');
form.append('file', new Blob(['fake-image'], { type: 'image/jpeg' }), 'front.jpg');

const imageResponse = await fetch(`${baseUrl}/api/v1/sessions/${sessionId}/images`, {
  method: 'POST',
  headers: authHeaders,
  body: form
});
if (!imageResponse.ok) {
  throw new Error(`Failed uploading image: ${imageResponse.status} ${await imageResponse.text()}`);
}

const resultResponse = await fetch(`${baseUrl}/api/v1/sessions/${sessionId}/results`, {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json',
    ...authHeaders
  },
  body: JSON.stringify({ sessionId, status: { overall: 1 }, documentTypes: [{ name: 'Smoke ID' }] })
});
if (!resultResponse.ok) {
  throw new Error(`Failed posting result: ${resultResponse.status} ${await resultResponse.text()}`);
}

const sessionDetail = await fetch(`${baseUrl}/api/v1/sessions/${sessionId}`, {
  headers: authHeaders
});
const sessionJson = await sessionDetail.json();
if (!sessionJson.result?.summary?.verdict) {
  throw new Error('Session result summary was not generated');
}

const summaryResponse = await fetch(`${baseUrl}/api/v1/sessions/${sessionId}/summary`, {
  headers: authHeaders
});
if (!summaryResponse.ok) {
  throw new Error(`Failed getting summary: ${summaryResponse.status} ${await summaryResponse.text()}`);
}

console.log(await summaryResponse.json());
