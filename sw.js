const CACHE_NAME = 'michufit-cloud-v104';
const ASSETS = ['./', './index.html', './privacidad.html', './terminos.html', './eliminar-cuenta.html', './manifest.json', './icon.svg', './icon-192.png', './icon-512.png', './icon-maskable-512.png', './apple-touch-icon.png'];

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
  // Jamás interceptar ni reescribir peticiones cross-origin (Google OAuth, Supabase, APIs externas) ni llamadas POST/API
  if (e.request.method !== 'GET' || !e.request.url.startsWith(self.location.origin) || e.request.url.includes('/api/') || e.request.url.includes('.apk')) return;
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

let _swSolarAlarmTimer = null;

self.addEventListener('message', (event) => {
  const data = event.data || {};
  if (data.type === 'CANCEL_SOLAR_ALARM') {
    if (_swSolarAlarmTimer) {
      clearTimeout(_swSolarAlarmTimer);
      _swSolarAlarmTimer = null;
    }
  } else if (data.type === 'SCHEDULE_SOLAR_ALARM') {
    if (_swSolarAlarmTimer) clearTimeout(_swSolarAlarmTimer);
    const delay = Math.max(1000, Number(data.delayMs) || 0);
    const mins = Number(data.minutesTarget) || 10;
    event.waitUntil(
      new Promise((resolve) => {
        _swSolarAlarmTimer = setTimeout(() => {
          _swSolarAlarmTimer = null;
          self.registration.showNotification('☀️ ¡Dosis de Vitamina D completada!', {
            body: `Cumpliste tus ${mins} min de sol seguro. Pasá a la sombra o aplicate protector solar.`,
            icon: './icon-192.png',
            badge: './icon-192.png',
            vibrate: [450, 160, 450, 160, 650],
            tag: 'michufit-vitd-timer',
            renotify: true,
            requireInteraction: true
          }).catch(() => {}).finally(resolve);
        }, delay);
      })
    );
  }
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clientList) => {
      for (const client of clientList) {
        if ('focus' in client) return client.focus();
      }
      if (self.clients.openWindow) return self.clients.openWindow('./');
    })
  );
});
