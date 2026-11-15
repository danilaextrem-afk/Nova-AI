// Nonblocking preview-release evidence: an APK build is not proof that Render
// deployed the matching backend. Never claims real AI/chat has been validated.
import { fileURLToPath } from 'node:url';

const SHA = /^[a-f0-9]{7,40}$/i;
const HTTP_TIMEOUT_MS = 12_000;

export async function inspectPreviewBackend(baseUrl, expectedSha, get = fetch) {
  const expected = typeof expectedSha === 'string' && SHA.test(expectedSha)
    ? expectedSha.slice(0, 7).toLowerCase() : null;
  const report = {
    status: 'NOT_CONFIGURED',
    expected,
    deployed: null,
    healthHttp: 0,
    readyHttp: 0,
  };
  // Only public HTTPS origin addresses are supported. The URL is never
  // logged with credentials, query parameters, or fragments.
  if (typeof baseUrl !== 'string' ||
      !/^https:\/\/[a-z0-9.-]+\/?$/i.test(baseUrl.trim())) return report;
  const base = baseUrl.trim().replace(/\/$/, '');
  const read = async path => {
    try {
      const response = await get(base + path, {
        method: 'GET', redirect: 'error',
        signal: AbortSignal.timeout(HTTP_TIMEOUT_MS),
      });
      return { http: response.status, body: response.status === 200
        ? await response.json().catch(() => null) : null };
    } catch {
      return { http: 0, body: null };
    }
  };
  const [health, ready] = await Promise.all([read('/health'), read('/ready')]);
  report.healthHttp = health.http;
  report.readyHttp = ready.http;
  const liveSha = health.body?.commit;
  if (typeof liveSha === 'string' && SHA.test(liveSha)) report.deployed = liveSha.slice(0,7).toLowerCase();
  if (health.http !== 200 || ready.http !== 200 || health.body?.ok !== true || ready.body?.ok !== true) {
    report.status = 'UNAVAILABLE'; return report;
  }
  if (!report.deployed || !expected) {
    report.status = 'UNVERIFIED'; return report;
  }
  report.status = report.deployed === expected ? 'COMMIT_MATCHED' : 'STALE_BACKEND';
  return report;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const result = await inspectPreviewBackend(process.argv[2] ?? '', process.argv[3] ?? '');
  process.stdout.write(JSON.stringify(result) + '\n');
}
