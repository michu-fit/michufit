const CACHE_NAME = 'michufit-cloud-v51';
const ASSETS = ['./', './index.html', './manifest.json', './icon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then(async (cache) => {
      for (const asset of ASSETS) {
        try {
          const freshRes = await fetch(asset, { cache: 'reload' });
          if (freshRes && freshRes.ok) {
            await cache.put(asset, freshRes);
          }
        } catch (_) {}
      }
    })
  );
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

function fetchWithTimeout(urlStr, ms = 12000) {
  return new Promise((resolve, reject) => {
    const ctrl = new AbortController();
    const timer = setTimeout(() => {
      ctrl.abort();
      reject(new Error('sw-timeout'));
    }, ms);
    const bustUrl = urlStr + (urlStr.includes('?') ? '&' : '?') + '_v=' + Date.now();
    fetch(bustUrl, { cache: 'no-store', signal: ctrl.signal })
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
  if (e.request.method !== 'GET' || e.request.url.includes('/api/') || e.request.url.includes('supabase.co') || e.request.url.includes('openfoodfacts.org') || e.request.url.endsWith('.apk')) return;
  e.respondWith(
    fetchWithTimeout(e.request.url, 12000)
      .then((res) => {
        if (res && res.status === 200) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((c) => {
            c.put(e.request, clone);
            if (e.request.mode === 'navigate') {
              c.put('./', res.clone());
              c.put('./index.html', res.clone());
            }
          });
          return res;
        }
        return caches.match(e.request).then((c) => c || caches.match('./index.html') || caches.match('./'));
      })
      .catch(() => caches.match(e.request).then((c) => c || caches.match('./index.html') || caches.match('./')))
  );
});
