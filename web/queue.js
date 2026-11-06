import { request } from './api.js';

const IGNORE_INTERVAL_MS = 500;
const IGNORE_RETRY_AFTER_MS = 3000;

export async function register(eventId) {
  const response = await request('POST', `/api/events/${eventId}/queues`);
  return { ...response, token: response.headers.get('X-Queue-Token') };
}

// options: followInterval(), followRetryAfter()가 true를 돌려주면 서버 지시를 따른다
export function startPolling(eventId, token, options, handlers) {
  let stopped = false;
  let timer = null;
  let polls = 0;

  const schedule = (ms) => {
    if (!stopped) timer = setTimeout(poll, ms);
  };

  async function poll() {
    polls += 1;
    const response = await request('GET', `/api/events/${eventId}/queues/me`, {
      headers: { 'X-Queue-Token': token },
    });
    if (stopped) return;
    handlers.onResponse({ ...response, polls });

    if (response.status === 200) {
      if (response.data.rank === 0) {
        stopped = true;
        handlers.onActive(response.data);
        return;
      }
      schedule(options.followInterval() ? response.data.nextPollIntervalMs : IGNORE_INTERVAL_MS);
      return;
    }
    if (response.status === 401) {
      stopped = true;
      handlers.onInvalid(response);
      return;
    }
    const followRetryAfter = options.followRetryAfter() && response.retryAfter !== null;
    schedule(followRetryAfter ? response.retryAfter * 1000 : IGNORE_RETRY_AFTER_MS);
  }

  poll();

  return {
    stop() {
      stopped = true;
      clearTimeout(timer);
    },
  };
}
