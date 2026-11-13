const CACHE_NAME = 'michufit-cloud-v7';
const ASSETS = ['./', './index.html', './manifest.json', './icon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE_NAME).then((c) => c.addAll(ASSETS)).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

function fetchWithTimeout(req, ms = 2500) {
  return new Promise((resolve, reject) => {
    const ctrl = new AbortController();
    const timer = setTimeout(() => {
      ctrl.abort();
      reject(new Error('sw-timeout'));
    }, ms);
    fetch(req, { cache: 'no-store', signal: ctrl.signal })
      .then((res) => {
        clearTimeout(timer);
        resolve(res);
      })
      .catch((err) => {
        clearTimeout(timer);
        reject(err);
      });
  });
}

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET' || e.request.url.includes('/api/') || e.request.url.includes('supabase.co')) return;
  e.respondWith(
    fetchWithTimeout(e.request, 2500)
      .then((res) => {
        if (res && res.status === 200) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((c) => c.put(e.request, clone));
          return res;
        }
        return caches.match(e.request).then((c) => c || caches.match('./index.html') || caches.match('./'));
      })
      .catch(() => caches.match(e.request).then((c) => c || caches.match('./index.html') || caches.match('./')))
  );
});
