const listeners = [];

export function onLog(listener) {
  listeners.push(listener);
}

function parseJson(text) {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

// 요청마다 상태 코드, 지연, 주요 헤더를 로그 리스너에 알린다
export async function request(method, path, { headers = {}, body } = {}) {
  const init = { method, headers: { ...headers } };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }

  const startedAt = performance.now();
  let response;
  let text;
  try {
    response = await fetch(path, init);
    text = await response.text();
  } catch (error) {
    const result = { status: 0, ok: false, headers: new Headers(), data: null, retryAfter: null, error: String(error) };
    notify(method, path, result, startedAt, 0);
    return result;
  }

  const data = parseJson(text);
  const retryAfter = response.headers.get('Retry-After');
  const result = {
    status: response.status,
    ok: response.ok,
    headers: response.headers,
    data,
    retryAfter: retryAfter === null ? null : Number(retryAfter),
    error: null,
  };
  notify(method, path, result, startedAt, text.length);
  return result;
}

function notify(method, path, result, startedAt, bytes) {
  const entry = {
    at: new Date(),
    method,
    path,
    status: result.status,
    ms: Math.round(performance.now() - startedAt),
    bytes,
    retryAfter: result.retryAfter,
    cacheStatus: result.headers.get('X-Cache-Status'),
    requestId: result.headers.get('X-Request-Id'),
    code: result.data && result.data.code ? result.data.code : null,
    error: result.error,
  };
  listeners.forEach((listener) => listener(entry));
}
