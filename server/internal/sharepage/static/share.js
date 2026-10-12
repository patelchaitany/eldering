// Eldering public alert page: progressive enhancement over the server-rendered
// text. CSP allows only same-origin scripts, so there is no inline script, and
// every piece of data is written with textContent (never innerHTML).
import { Map, Marker, NavigationControl, LngLatBounds } from './maplibre-gl/6.11.2/maplibre-gl.mjs';

const STYLE = 'https://tiles.openfreemap.org/styles/liberty';
const POLL_MS = 15000;
const RED = '#d03030';

const token = location.pathname.split('/')[2] || '';
const dataURL = `/s/${encodeURIComponent(token)}/data`;
const byId = (id) => document.getElementById(id);
const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const timeFmt = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' });

let map = null;
let marker = null;
let mapReady = false;
let timer = null;
let hadLatest = Boolean(byId('latest-at'));

function localizeTimes(root) {
  for (const el of root.querySelectorAll('time.ts')) {
    const d = new Date(el.dateTime);
    if (!Number.isNaN(d.getTime())) {
      el.textContent = dateFmt.format(d);
      el.title = d.toISOString();
    }
  }
}

function ago(iso) {
  const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (s < 90) return `(${s} s ago)`;
  const m = Math.round(s / 60);
  if (m < 90) return `(${m} min ago)`;
  return `(${Math.round(m / 60)} h ago)`;
}

function note(text) {
  const el = byId('map-note');
  if (!el) return;
  el.textContent = text;
  el.hidden = false;
}

function trailFeature(d) {
  return {
    type: 'Feature',
    properties: {},
    geometry: { type: 'LineString', coordinates: d.trail.map((p) => [p.lng, p.lat]) },
  };
}

function landmarkFeatures(d) {
  return {
    type: 'FeatureCollection',
    features: d.trail
      .filter((p) => p.landmark)
      .map((p) => ({ type: 'Feature', properties: { name: p.landmark }, geometry: { type: 'Point', coordinates: [p.lng, p.lat] } })),
  };
}

function fit(d) {
  if (!map || !d.latest) return;
  if (d.trail.length < 2) {
    map.jumpTo({ center: [d.latest.lng, d.latest.lat] });
    return;
  }
  const b = new LngLatBounds();
  for (const p of d.trail) b.extend([p.lng, p.lat]);
  b.extend([d.latest.lng, d.latest.lat]);
  map.fitBounds(b, { padding: 48, maxZoom: 16, duration: 0 });
}

function initMap(d) {
  const el = byId('map');
  if (!el) return;
  if (!d.latest) {
    el.hidden = true;
    return;
  }
  try {
    map = new Map({
      container: el,
      style: STYLE,
      center: [d.latest.lng, d.latest.lat],
      zoom: 15,
      attributionControl: { compact: true },
    });
  } catch (err) {
    el.hidden = true;
    note('The map cannot be shown on this device. Use the location links below.');
    return;
  }
  map.addControl(new NavigationControl({ showCompass: false }));
  let styleFailed = false;
  map.on('error', () => {
    if (!mapReady && !styleFailed) {
      styleFailed = true;
      note('Map tiles are unavailable right now. The coordinates and links below still work.');
    }
  });
  map.on('load', () => {
    map.addSource('trail', { type: 'geojson', data: trailFeature(d) });
    map.addLayer({
      id: 'trail-line',
      type: 'line',
      source: 'trail',
      layout: { 'line-join': 'round', 'line-cap': 'round' },
      paint: { 'line-color': RED, 'line-width': 4, 'line-opacity': 0.85 },
    });
    map.addSource('landmarks', { type: 'geojson', data: landmarkFeatures(d) });
    map.addLayer({
      id: 'landmarks',
      type: 'circle',
      source: 'landmarks',
      paint: { 'circle-radius': 6, 'circle-color': '#ffffff', 'circle-stroke-color': RED, 'circle-stroke-width': 3 },
    });
    mapReady = true;
    fit(d);
  });
  marker = new Marker({ color: RED }).setLngLat([d.latest.lng, d.latest.lat]).addTo(map);
}

function renderLandmarks(d) {
  const list = byId('landmarks');
  if (!list) return;
  const items = d.trail.filter((p) => p.landmark);
  list.replaceChildren();
  if (items.length === 0) {
    const li = document.createElement('li');
    li.className = 'muted';
    li.textContent = 'No landmarks recorded.';
    list.append(li);
    return;
  }
  for (const p of items) {
    const li = document.createElement('li');
    const t = document.createElement('time');
    t.dateTime = p.recorded_at;
    t.textContent = timeFmt.format(new Date(p.recorded_at));
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'place';
    btn.textContent = p.landmark;
    btn.addEventListener('click', () => {
      if (map) map.flyTo({ center: [p.lng, p.lat], zoom: 16 });
    });
    li.append(t, btn);
    list.append(li);
  }
}

function renderLatest(d) {
  if (!d.latest) return;
  if (!hadLatest) {
    location.reload(); // first fix arrived: re-render the full server page
    return;
  }
  const { lat, lng } = d.latest;
  byId('latest-coords').textContent = `${lat.toFixed(6)}, ${lng.toFixed(6)}`;
  const acc = byId('latest-acc');
  if (acc) acc.textContent = d.latest.accuracy_m == null ? '' : `±${Math.round(d.latest.accuracy_m)} m`;
  const at = byId('latest-at');
  if (at) {
    at.dateTime = d.latest.recorded_at;
    at.textContent = dateFmt.format(new Date(d.latest.recorded_at));
  }
  const agoEl = byId('latest-ago');
  if (agoEl) agoEl.textContent = ago(d.latest.recorded_at);
  const osm = byId('osm-link');
  if (osm) {
    const la = lat.toFixed(6);
    const lo = lng.toFixed(6);
    osm.href = `https://www.openstreetmap.org/?mlat=${la}&mlon=${lo}#map=17/${la}/${lo}`;
  }
  const geo = byId('geo-link');
  if (geo) geo.href = `geo:${lat.toFixed(6)},${lng.toFixed(6)}`;
}

function update(d) {
  renderLatest(d);
  renderLandmarks(d);
  if (!map || !mapReady || !d.latest) return;
  map.getSource('trail').setData(trailFeature(d));
  map.getSource('landmarks').setData(landmarkFeatures(d));
  marker.setLngLat([d.latest.lng, d.latest.lat]);
}

function stop() {
  if (timer) clearInterval(timer);
  timer = null;
}

async function fetchData() {
  const res = await fetch(dataURL, { cache: 'no-store', credentials: 'omit' });
  if (res.status === 404) {
    stop();
    note('This link has expired.');
    return null;
  }
  if (!res.ok) return null;
  return res.json();
}

async function poll() {
  try {
    const d = await fetchData();
    if (!d) return;
    if (d.status !== 'active') {
      stop();
      location.reload(); // show the resolved state rendered by the server
      return;
    }
    update(d);
  } catch {
    // Offline or transient error: keep showing the last data.
  }
}

async function main() {
  localizeTimes(document);
  const card = byId('status-card');
  if (!card || card.dataset.active !== 'true') return;
  try {
    const d = await fetchData();
    if (!d) return;
    initMap(d);
    update(d);
  } catch {
    note('Could not load live data. The details below may be out of date.');
  }
  timer = setInterval(poll, POLL_MS);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && timer) poll();
  });
}

main();
