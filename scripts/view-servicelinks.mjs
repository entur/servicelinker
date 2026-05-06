#!/usr/bin/env node
// view-servicelinks <file.xml|file.zip> [...]
// Extracts ServiceLink geometries and TopographicPlace centroids from any combination of NeTEx XML/ZIP files
// and opens an interactive Leaflet map in the browser.

import { readFileSync, writeFileSync, mkdtempSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, basename, extname } from 'node:path';

const inputs = process.argv.slice(2);

if (inputs.length === 0) {
  console.error('Usage: view-servicelinks <file.xml|file.zip> [...]');
  process.exit(1);
}
for (const p of inputs) {
  if (!existsSync(p)) {
    console.error(`File not found: ${p}`);
    process.exit(1);
  }
}

function parsePosListToCoords(text) {
  const nums = text.trim().split(/\s+/).map(Number);
  // posList is lat lon pairs — GeoJSON wants [lon, lat]
  return Array.from({ length: Math.floor(nums.length / 2) }, (_, i) => [nums[i * 2 + 1], nums[i * 2]]);
}

function buildStopNames(xmlList) {
  const sspNames = new Map();   // sspId → name
  const sspToQuay = new Map();  // sspId → quayId
  const quayNames = new Map();  // quayId → name

  for (const xml of xmlList) {
    // ScheduledStopPoint names
    for (const [, id, body] of xml.matchAll(/<ScheduledStopPoint\b[^>]*\bid="([^"]+)"[^>]*>([\s\S]*?)<\/ScheduledStopPoint>/g)) {
      const name = /<Name[^>]*>([^<]+)<\/Name>/.exec(body)?.[1];
      if (name) sspNames.set(id, name.trim());
    }

    // PassengerStopAssignment: ScheduledStopPoint → Quay
    for (const [, body] of xml.matchAll(/<PassengerStopAssignment\b[^>]*>([\s\S]*?)<\/PassengerStopAssignment>/g)) {
      const sspRef  = /<ScheduledStopPointRef\b[^>]+ref="([^"]+)"/.exec(body)?.[1];
      const quayRef = /<QuayRef\b[^>]+ref="([^"]+)"/.exec(body)?.[1];
      if (sspRef && quayRef) sspToQuay.set(sspRef, quayRef);
    }

    // Quay names (typically in _shared_data.xml)
    for (const [, id, body] of xml.matchAll(/<Quay\b[^>]*\bid="([^"]+)"[^>]*>([\s\S]*?)<\/Quay>/g)) {
      const name = /<Name[^>]*>([^<]+)<\/Name>/.exec(body)?.[1];
      if (name) quayNames.set(id, name.trim());
    }
  }

  return stopRef => {
    const quayId = sspToQuay.get(stopRef) ?? null;
    return { name: quayNames.get(quayId) ?? sspNames.get(stopRef) ?? null, quay: quayId };
  };
}

function extractFeatures(xml, resolveName) {
  const linkRe = /<ServiceLink[^>]*\bid="([^"]+)"[\s\S]*?<\/ServiceLink>/g;
  const posRe  = /<(?:\w+:)?posList[^>]*>([\s\S]*?)<\/(?:\w+:)?posList>/;
  const fromRe = /<FromPointRef[^>]+ref="([^"]+)"/;
  const toRe   = /<ToPointRef[^>]+ref="([^"]+)"/;

  return [...xml.matchAll(linkRe)].flatMap(([block, id]) => {
    const coords = parsePosListToCoords(posRe.exec(block)?.[1] ?? '');
    if (coords.length < 2) return [];
    const from = fromRe.exec(block)?.[1] ?? null;
    const to   = toRe.exec(block)?.[1]   ?? null;
    const { name: fromName, quay: fromQuay } = from ? resolveName(from) : {};
    const { name: toName,   quay: toQuay   } = to   ? resolveName(to)   : {};
    return [{ type: 'Feature', properties: { id, from, to, fromName, fromQuay, toName, toQuay }, geometry: { type: 'LineString', coordinates: coords } }];
  });
}

function extractTopographicPlaces(xml) {
  const placeRe = /<TopographicPlace\b[^>]*\bid="([^"]+)"[^>]*>([\s\S]*?)<\/TopographicPlace>/g;
  return [...xml.matchAll(placeRe)].flatMap(([, id, body]) => {
    const lon = Number(/<Longitude>([^<]+)<\/Longitude>/.exec(body)?.[1]);
    const lat = Number(/<Latitude>([^<]+)<\/Latitude>/.exec(body)?.[1]);
    // Pick the TopographicPlace's own Name, not a nested Descriptor/Name — match before Centroid.
    const head = body.split(/<Centroid\b/)[0];
    const name = /<Name[^>]*>([^<]+)<\/Name>/.exec(head)?.[1]?.trim() ?? null;
    const type = /<TopographicPlaceType>([^<]+)<\/TopographicPlaceType>/.exec(body)?.[1] ?? null;
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) return [];
    return [{ type: 'Feature', properties: { id, name, type }, geometry: { type: 'Point', coordinates: [lon, lat] } }];
  });
}

function readXmls(path) {
  if (extname(path).toLowerCase() !== '.zip') {
    return [{ name: basename(path), xml: readFileSync(path, 'utf8') }];
  }
  const tmp = mkdtempSync(join(tmpdir(), 'view-servicelinks-'));
  try {
    const r = spawnSync('unzip', ['-q', path, '-d', tmp], { stdio: 'inherit' });
    if (r.status !== 0) throw new Error(`unzip failed for ${path}`);
    const xmlFiles = readdirSync(tmp, { recursive: true })
      .filter(f => f.toLowerCase().endsWith('.xml'))
      .map(f => join(tmp, f));
    return xmlFiles.map(f => ({ name: `${basename(path)}:${basename(f)}`, xml: readFileSync(f, 'utf8') }));
  } finally {
    rmSync(tmp, { recursive: true, force: true });
  }
}

// Escape characters that would terminate the inline <script> block when embedding JSON.
const safeJson = obj => JSON.stringify(obj).replace(/</g, '\\u003c').replace(/-->/g, '--\\u003e').replace(/]]>/g, ']]\\u003e');

const xmls = inputs.flatMap(readXmls);
const resolveName = buildStopNames(xmls.map(x => x.xml));

const features = xmls.flatMap(({ name, xml }) => {
  const found = extractFeatures(xml, resolveName);
  if (found.length > 0) console.log(`  ${name}: ${found.length} ServiceLinks`);
  return found;
});
console.log(`Total: ${features.length} ServiceLinks`);

const pois = xmls.flatMap(({ name, xml }) => {
  const found = extractTopographicPlaces(xml);
  if (found.length > 0) console.log(`  ${name}: ${found.length} TopographicPlaces`);
  return found;
});
console.log(`Total: ${pois.length} TopographicPlaces`);

const html = `<!DOCTYPE html>
<html lang="no">
<head>
  <meta charset="utf-8"/>
  <title>${inputs.map(p => basename(p)).join(', ')}</title>
  <link rel="stylesheet" href="https://unpkg.com/maplibre-gl@5.19.0/dist/maplibre-gl.css"/>
  <script src="https://unpkg.com/maplibre-gl@5.19.0/dist/maplibre-gl.js"></script>
  <style>html, body, #map { margin: 0; padding: 0; height: 100% }</style>
</head>
<body>
<div id="map"></div>
<script>
  const map = new maplibregl.Map({
    container: 'map',
    style: {
      version: 8,
      sources: { osm: { type: 'raster', tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'], tileSize: 256, attribution: '© OpenStreetMap contributors' } },
      layers:  [{ id: 'osm', type: 'raster', source: 'osm' }],
    },
    center: [14, 63], zoom: 4,
  });

  const geojson = ${safeJson({ type: 'FeatureCollection', features })};
  const poiGeojson = ${safeJson({ type: 'FeatureCollection', features: pois })};

  const stops = {
    type: 'FeatureCollection',
    features: geojson.features.flatMap(f => {
      const coords = f.geometry.coordinates;
      const { id, from, to, fromName, fromQuay, toName, toQuay } = f.properties;
      return [coords.at(0), coords.at(-1)].map((c, i) => (
        { type: 'Feature', properties: { id, stop: i === 0 ? from : to, name: i === 0 ? fromName : toName, quay: i === 0 ? fromQuay : toQuay }, geometry: { type: 'Point', coordinates: c } }
      ));
    }),
  };

  map.on('load', () => {
    map.addSource('links', { type: 'geojson', data: geojson });
    map.addLayer({ id: 'links', type: 'line', source: 'links',
                   paint: { 'line-color': '#e63946', 'line-width': 2, 'line-opacity': 0.8 } });

    map.addSource('stops', { type: 'geojson', data: stops });
    map.addLayer({ id: 'stops', type: 'circle', source: 'stops',
                   paint: { 'circle-radius': 5, 'circle-color': '#e63946', 'circle-stroke-width': 1, 'circle-stroke-color': '#fff' } });

    map.addSource('pois', { type: 'geojson', data: poiGeojson });
    map.addLayer({ id: 'pois', type: 'circle', source: 'pois',
                   paint: { 'circle-radius': 5, 'circle-color': '#1d4ed8', 'circle-stroke-width': 1, 'circle-stroke-color': '#fff' } });

    const tooltip = new maplibregl.Popup({ closeButton: false, closeOnClick: false });
    map.on('mousemove',  'links', e => tooltip.setLngLat(e.lngLat).setHTML(e.features[0].properties.id).addTo(map));
    map.on('mouseleave', 'links', () => tooltip.remove());
    map.on('mouseenter', 'links', () => map.getCanvas().style.cursor = 'pointer');
    map.on('mouseleave', 'links', () => map.getCanvas().style.cursor = '');

    map.on('mousemove',  'stops', e => { const p = e.features[0].properties; const label = p.name && p.quay ? p.name + ' (' + p.quay + ')' : p.name || p.quay || p.stop || p.id; tooltip.setLngLat(e.lngLat).setHTML(label).addTo(map); });
    map.on('mouseleave', 'stops', () => tooltip.remove());
    map.on('mouseenter', 'stops', () => map.getCanvas().style.cursor = 'pointer');
    map.on('mouseleave', 'stops', () => map.getCanvas().style.cursor = '');

    map.on('mousemove',  'pois', e => { const p = e.features[0].properties; const label = p.name ? p.name + ' (' + p.id + ')' : p.id; tooltip.setLngLat(e.lngLat).setHTML(label).addTo(map); });
    map.on('mouseleave', 'pois', () => tooltip.remove());
    map.on('mouseenter', 'pois', () => map.getCanvas().style.cursor = 'pointer');
    map.on('mouseleave', 'pois', () => map.getCanvas().style.cursor = '');

    const linkCoords = geojson.features.flatMap(f => f.geometry.coordinates);
    const poiCoords = poiGeojson.features.map(f => f.geometry.coordinates);
    const allCoords = linkCoords.concat(poiCoords);
    if (allCoords.length > 0) {
      const bounds = allCoords.reduce((b, c) => b.extend(c), new maplibregl.LngLatBounds());
      map.fitBounds(bounds, { padding: 40, maxZoom: 14 });
    } else {
      document.getElementById('map').insertAdjacentHTML('afterbegin', \`
        <div style="position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);
                    background:white;padding:1em;border-radius:4px;z-index:999">
          No ServiceLinks or TopographicPlaces found
        </div>\`);
    }
  });
</script>
</body>
</html>`;

const out = join(tmpdir(), `view-servicelinks-${Date.now()}.html`);
writeFileSync(out, html);
spawnSync(process.platform === 'darwin' ? 'open' : 'xdg-open', [out]);
console.log(`Opened ${out}`);
