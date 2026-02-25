#!/usr/bin/env node
// view-servicelinks <netex.xml|netex.zip>
// Extracts ServiceLink geometries and opens an interactive Leaflet map in the browser.

import { readFileSync, writeFileSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { execSync, spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, basename, extname } from 'node:path';

const [, , path] = process.argv;

if (!path || !existsSync(path)) {
  console.error(path ? `File not found: ${path}` : 'Usage: view-servicelinks <netex.xml|netex.zip>');
  process.exit(1);
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
  const posRe  = /<\w+:posList[^>]*>([\s\S]*?)<\/\w+:posList>/;
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

function collectFeatures(path) {
  if (extname(path).toLowerCase() !== '.zip') {
    const xml = readFileSync(path, 'utf8');
    return extractFeatures(xml, buildStopNames([xml]));
  }

  const tmp = mkdtempSync(join(tmpdir(), 'view-servicelinks-'));
  try {
    execSync(`unzip -q "${path}" -d "${tmp}"`);
    const xmlFiles = execSync(`find "${tmp}" -name "*.xml"`, { encoding: 'utf8' })
      .trim().split('\n').filter(Boolean);
    console.log(`Processing ${xmlFiles.length} XML file(s) in ZIP...`);
    const xmlList = xmlFiles.map(f => readFileSync(f, 'utf8'));
    const resolveName = buildStopNames(xmlList);
    return xmlList.flatMap((xml, i) => {
      const found = extractFeatures(xml, resolveName);
      console.log(`  ${basename(xmlFiles[i])}: ${found.length} ServiceLinks`);
      return found;
    });
  } finally {
    rmSync(tmp, { recursive: true });
  }
}

const features = collectFeatures(path);
console.log(`Total: ${features.length} ServiceLinks`);

const html = `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8"/>
  <title>${basename(path)}</title>
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

  const geojson = ${JSON.stringify({ type: 'FeatureCollection', features })};

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

    const tooltip = new maplibregl.Popup({ closeButton: false, closeOnClick: false });
    map.on('mousemove',  'links', e => tooltip.setLngLat(e.lngLat).setHTML(e.features[0].properties.id).addTo(map));
    map.on('mouseleave', 'links', () => tooltip.remove());
    map.on('mouseenter', 'links', () => map.getCanvas().style.cursor = 'pointer');
    map.on('mouseleave', 'links', () => map.getCanvas().style.cursor = '');

    map.on('mousemove',  'stops', e => { const p = e.features[0].properties; const label = p.name && p.quay ? p.name + ' (' + p.quay + ')' : p.name || p.quay || p.stop || p.id; tooltip.setLngLat(e.lngLat).setHTML(label).addTo(map); });
    map.on('mouseleave', 'stops', () => tooltip.remove());
    map.on('mouseenter', 'stops', () => map.getCanvas().style.cursor = 'pointer');
    map.on('mouseleave', 'stops', () => map.getCanvas().style.cursor = '');

    if (geojson.features.length > 0) {
      const bounds = geojson.features.flatMap(f => f.geometry.coordinates)
        .reduce((b, c) => b.extend(c), new maplibregl.LngLatBounds());
      map.fitBounds(bounds, { padding: 40 });
    } else {
      document.getElementById('map').insertAdjacentHTML('afterbegin', \`
        <div style="position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);
                    background:white;padding:1em;border-radius:4px;z-index:999">
          No ServiceLinks found
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
