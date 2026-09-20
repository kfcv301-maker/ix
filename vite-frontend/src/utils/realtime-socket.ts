import axios from 'axios';

/**
 * Convert the HTTP API base URL into an absolute WebSocket URL.
 *
 * The API normally uses a relative `/api/v1/` base path. Passing that value
 * straight to `new WebSocket()` works in neither Safari nor Chromium because
 * WebSocket URLs must use an explicit ws:// or wss:// protocol. Resolving it
 * against `window.location` also keeps WebView and custom panel addresses
 * working.
 */
export const getRealtimeSocketUrl = (): string => {
  const configuredBase = axios.defaults.baseURL
    || (import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/');
  const apiUrl = new URL(configuredBase, window.location.href);

  apiUrl.protocol = apiUrl.protocol === 'https:' ? 'wss:' : 'ws:';
  apiUrl.pathname = `${apiUrl.pathname.replace(/\/api\/v1\/?$/, '').replace(/\/$/, '')}/system-info`;
  apiUrl.search = '';
  apiUrl.searchParams.set('type', '0');

  const token = localStorage.getItem('token');
  if (token) {
    apiUrl.searchParams.set('secret', token);
  }

  return apiUrl.toString();
};
