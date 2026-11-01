'use strict';

// The page of Ultra Fast Pregen Manager. Text from the server only ever goes in through textContent.

const $ = id => document.getElementById(id);
const REGION = 512;
const CHUNK = 16;
const PALETTE = ['#4f9fe0', '#e0883f', '#57b36a', '#c36bd6', '#d9b23c', '#4cc0b5', '#e0637a', '#8d8fe3', '#9aa83c', '#b57c52'];
// Regions in view, above which the terrain waits for a closer look; and how often a region that changes is drawn again.
const TERRAIN_REGIONS = 400;
const TERRAIN_RETRY = 6000;
const number = new Intl.NumberFormat('en-US');

const state = {
  token: null,
  info: null,
  tab: null,
  shape: 'square',
  since: null,
  running: false,
  ufp: null,
  link: null,
  plan: null,
  brush: -1,
  lastRegions: 0,
  terrain: false,
};

// Storage can be missing in a private window: the token then lasts as long as the page.
function loadToken() {
  try { return localStorage.getItem('ufpmanager-token'); } catch { return null; }
}
function saveToken(token) {
  try {
    if (token) localStorage.setItem('ufpmanager-token', token);
    else localStorage.removeItem('ufpmanager-token');
  } catch { /* the page still works */ }
}

async function request(path, options) {
  const response = await fetch(path, { ...options, headers: { Authorization: 'Bearer ' + state.token, ...(options && options.headers) } });
  if (response.status === 401) {
    const failure = new Error('wrong token');
    failure.status = 401;
    throw failure;
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.error || response.statusText);
  }
  return response;
}

const get = async path => (await request(path)).json();

async function post(path, fields) {
  const form = new URLSearchParams();
  for (const [key, value] of Object.entries(fields || {})) {
    if (Array.isArray(value)) value.forEach(item => form.append(key, item));
    else form.append(key, value);
  }
  const response = await request(path, { method: 'POST', body: form });
  return response.json();
}

let toastTimer;
function toast(message) {
  const element = $('toast');
  element.textContent = message;
  element.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { element.hidden = true; }, 6000);
}

function duration(ms) {
  if (ms == null || ms < 0) return 'Working it out';
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return seconds + ' s';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return minutes + ' min ' + (seconds % 60) + ' s';
  return Math.floor(minutes / 60) + ' h ' + (minutes % 60) + ' min';
}

// A machine keeps its color for as long as the page is open.
const colors = new Map();
function colorOf(name) {
  if (!colors.has(name)) colors.set(name, PALETTE[colors.size % PALETTE.length]);
  return colors.get(name);
}

function worldName(id) {
  return { 'minecraft:overworld': 'Overworld', 'minecraft:the_nether': 'Nether', 'minecraft:the_end': 'End' }[id] || id;
}

function element(tag, className, text) {
  const made = document.createElement(tag);
  if (className) made.className = className;
  if (text != null) made.textContent = text;
  return made;
}

// The map -----------------------------------------------------------------------------------------------------------

const canvas = $('map');
const context = canvas.getContext('2d');
const map = {
  x: 0, z: 0, scale: 0.12,
  width: 0, height: 0, ratio: 1,
  regions: new Map(),   // "x,z" -> {x, z, count, cells, image, dirty, asked}
  dirty: true,
  pointer: null,
  drawing: 0,
};

function css(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

function toScreenX(blockX) { return (blockX - map.x) * map.scale + map.width / 2; }
function toScreenZ(blockZ) { return (blockZ - map.z) * map.scale + map.height / 2; }
function toBlockX(screenX) { return (screenX - map.width / 2) / map.scale + map.x; }
function toBlockZ(screenZ) { return (screenZ - map.height / 2) / map.scale + map.z; }

function resize() {
  const box = canvas.getBoundingClientRect();
  map.ratio = window.devicePixelRatio || 1;
  map.width = box.width;
  map.height = box.height;
  canvas.width = Math.round(box.width * map.ratio);
  canvas.height = Math.round(box.height * map.ratio);
  map.dirty = true;
  if (map.fitLater && map.width > 0 && map.height > 0) fit();
}

function region(x, z) {
  const key = x + ',' + z;
  let found = map.regions.get(key);
  if (!found) {
    found = { x, z, count: 0, cells: null, image: null, dirty: true, asked: false, pictures: {}, askedAt: {} };
    map.regions.set(key, found);
  }
  return found;
}

function clearRegions() {
  map.regions.clear();
  map.dirty = true;
}

async function loadRegions() {
  const dimension = $('dimension').value;
  if (!dimension) return;
  state.lastRegions = Date.now();
  const answer = await get('/api/map/regions?dimension=' + encodeURIComponent(dimension));
  if (dimension !== $('dimension').value) return;
  for (const [x, z, count] of answer.regions) {
    const entry = region(x, z);
    if (entry.count !== count) {
      entry.count = count;
      entry.asked = false;
    }
  }
  map.dirty = true;
}

async function loadCells(entry) {
  entry.asked = true;
  const dimension = $('dimension').value;
  const response = await request('/api/map/region?dimension=' + encodeURIComponent(dimension) + '&x=' + entry.x + '&z=' + entry.z);
  const bits = new Uint8Array(await response.arrayBuffer());
  if (dimension !== $('dimension').value) return;
  const cells = entry.cells || new Uint8Array(1024);
  for (let i = 0; i < 1024; i++) {
    if (bits[i >> 3] & (1 << (i & 7)) && cells[i] === 0) cells[i] = 1;
  }
  entry.cells = cells;
  entry.dirty = true;
  map.dirty = true;
}

function markSaved(chunkX, chunkZ) {
  const entry = region(chunkX >> 5, chunkZ >> 5);
  if (!entry.cells) entry.cells = new Uint8Array(1024);
  const index = (chunkX & 31) + 32 * (chunkZ & 31);
  if (entry.cells[index] === 0) entry.count++;
  entry.cells[index] = 2;
  entry.dirty = true;
  map.dirty = true;
}

function regionImage(entry, disk, saved) {
  if (entry.image && !entry.dirty) return entry.image;
  const image = entry.image || Object.assign(document.createElement('canvas'), { width: 32, height: 32 });
  const painter = image.getContext('2d');
  const pixels = painter.createImageData(32, 32);
  for (let i = 0; i < 1024; i++) {
    const cell = entry.cells[i];
    if (cell === 0) continue;
    const color = cell === 2 ? saved : disk;
    pixels.data[4 * i] = color[0];
    pixels.data[4 * i + 1] = color[1];
    pixels.data[4 * i + 2] = color[2];
    pixels.data[4 * i + 3] = 255;
  }
  painter.putImageData(pixels, 0, 0);
  entry.image = image;
  entry.dirty = false;
  return image;
}

function visibleBlocks() {
  return { left: toBlockX(0), right: toBlockX(map.width), top: toBlockZ(0), bottom: toBlockZ(map.height) };
}

function drawRegions() {
  const disk = css('--disk').split(',').map(Number);
  const saved = css('--saved').split(',').map(Number);
  const zoomedIn = map.scale * CHUNK >= 1.2;
  const seen = visibleBlocks();
  context.imageSmoothingEnabled = false;
  let asking = 0;
  const pictureSize = terrainSize(seen);
  for (const entry of map.regions.values()) {
    const left = entry.x * REGION;
    const top = entry.z * REGION;
    if (left + REGION < seen.left || left > seen.right || top + REGION < seen.top || top > seen.bottom) continue;
    const x = toScreenX(left);
    const y = toScreenZ(top);
    const size = REGION * map.scale;
    if (zoomedIn && !entry.asked && asking < 6) {
      asking++;
      loadCells(entry).catch(() => { entry.asked = false; });
    }
    if (entry.cells) {
      context.drawImage(regionImage(entry, disk, saved), x, y, size, size);
    } else if (entry.count > 0) {
      context.fillStyle = `rgba(${css('--disk')}, ${0.25 + 0.75 * entry.count / 1024})`;
      context.fillRect(x, y, size, size);
    }
    if (pictureSize) {
      askPicture(entry, pictureSize);
      const picture = entry.pictures[pictureSize] || entry.pictures.small || entry.pictures.full;
      if (picture) context.drawImage(picture, x, y, size, size);
    }
  }
}

// Which picture of a region suits this zoom; null with the terrain off, or with too many regions in view.
function terrainSize(seen) {
  const shown = Math.ceil((seen.right - seen.left) / REGION + 1) * Math.ceil((seen.bottom - seen.top) / REGION + 1);
  const tooMany = state.terrain && (shown > TERRAIN_REGIONS || REGION * map.scale < 8);
  $('map-note').hidden = !tooMany && !state.noMods;
  if (state.noMods) return null;
  $('map-note').textContent = tooMany ? 'Zoom in to see the terrain.' : '';
  if (!state.terrain || tooMany) return null;
  return REGION * map.scale >= 180 ? 'full' : 'small';
}

function askPicture(entry, size) {
  if (entry.count === 0 || map.drawing >= 3) return;
  const fresh = entry.pictures[size] && entry.pictureCount === entry.count;
  if (fresh || Date.now() - (entry.askedAt[size] || 0) < TERRAIN_RETRY) return;
  entry.askedAt[size] = Date.now();
  map.drawing++;
  loadPicture(entry, size).catch(() => {}).finally(() => {
    map.drawing--;
    map.dirty = true;
  });
}

async function loadPicture(entry, size) {
  const dimension = $('dimension').value;
  const count = entry.count;
  const response = await request(`/api/map/terrain?dimension=${encodeURIComponent(dimension)}&x=${entry.x}&z=${entry.z}&size=${size}`);
  if (response.status !== 200 || dimension !== $('dimension').value) return;
  entry.pictures[size] = await createImageBitmap(await response.blob());
  entry.pictureCount = count;
}

function niceGrid() {
  for (const step of [16, 64, 512, 2048, 8192, 32768]) {
    if (step * map.scale >= 26) return step;
  }
  return 131072;
}

function drawGrid() {
  const step = niceGrid();
  const seen = visibleBlocks();
  context.strokeStyle = css('--map-grid');
  context.lineWidth = 1;
  context.beginPath();
  for (let x = Math.floor(seen.left / step) * step; x <= seen.right; x += step) {
    const at = Math.round(toScreenX(x)) + 0.5;
    context.moveTo(at, 0);
    context.lineTo(at, map.height);
  }
  for (let z = Math.floor(seen.top / step) * step; z <= seen.bottom; z += step) {
    const at = Math.round(toScreenZ(z)) + 0.5;
    context.moveTo(0, at);
    context.lineTo(map.width, at);
  }
  context.stroke();
  context.strokeStyle = css('--map-axis');
  context.beginPath();
  const originX = Math.round(toScreenX(0)) + 0.5;
  const originZ = Math.round(toScreenZ(0)) + 0.5;
  context.moveTo(originX, 0);
  context.lineTo(originX, map.height);
  context.moveTo(0, originZ);
  context.lineTo(map.width, originZ);
  context.stroke();
}

function tilesToShow() {
  const run = state.link && state.link.run;
  if (state.tab !== 'link') return null;
  if (run && run.running) return { plan: run, live: true };
  if (state.plan) return { plan: state.plan, live: false };
  return run ? { plan: run, live: true } : null;
}

function drawTiles() {
  const shown = tilesToShow();
  if (!shown) return false;
  const { plan, live } = shown;
  const side = plan.tileChunks * CHUNK;
  const size = side * map.scale;
  const seen = visibleBlocks();
  const pulse = 0.55 + 0.35 * Math.sin(Date.now() / 260);
  for (const [tileX, tileZ, machine, tileState, worker] of plan.tiles) {
    const left = tileX * side;
    const top = tileZ * side;
    if (left + side < seen.left || left > seen.right || top + side < seen.top || top > seen.bottom) continue;
    const name = live && worker >= 0 ? plan.names[worker] : plan.names[machine];
    const color = name == null ? css('--map-axis') : colorOf(name);
    let alpha = 0.34;
    if (live) alpha = tileState === 2 ? 0.8 : tileState === 1 ? pulse : 0.14;
    context.globalAlpha = alpha;
    context.fillStyle = color;
    context.fillRect(toScreenX(left), toScreenZ(top), size, size);
    context.globalAlpha = 1;
    if (size >= 5) {
      context.strokeStyle = color;
      context.lineWidth = tileState === 1 ? 2 : 1;
      context.strokeRect(toScreenX(left) + 0.5, toScreenZ(top) + 0.5, size - 1, size - 1);
    }
  }
  return true;
}

// The area drawn on the map: the form's, or the run's while one is active.
function areaToShow() {
  if (state.tab === 'link') {
    const size = Number($('link-size').value) * 1000;
    return { x: Number($('link-x').value), z: Number($('link-z').value), radius: size / 2, shape: 'square' };
  }
  const status = state.ufp && state.ufp.status;
  if (status && !status.ended) return { x: status.x, z: status.z, radius: status.radius, shape: status.shape };
  return { x: Number($('center-x').value), z: Number($('center-z').value), radius: Number($('size').value) / 2, shape: state.shape };
}

function drawArea() {
  const area = areaToShow();
  if (!area || !(area.radius > 0)) return;
  context.strokeStyle = css('--area');
  context.lineWidth = 1.5;
  context.setLineDash([6, 4]);
  const x = toScreenX(area.x);
  const y = toScreenZ(area.z);
  const r = area.radius * map.scale;
  context.beginPath();
  if (area.shape === 'circle') context.arc(x, y, r, 0, 2 * Math.PI);
  else context.rect(x - r, y - r, 2 * r, 2 * r);
  context.stroke();
  context.setLineDash([]);
  context.beginPath();
  context.moveTo(x - 5, y);
  context.lineTo(x + 5, y);
  context.moveTo(x, y - 5);
  context.lineTo(x, y + 5);
  context.stroke();
}

function draw() {
  context.setTransform(map.ratio, 0, 0, map.ratio, 0, 0);
  context.fillStyle = css('--map-bg');
  context.fillRect(0, 0, map.width, map.height);
  drawGrid();
  drawRegions();
  drawTiles();
  drawArea();
}

function frame() {
  const run = state.link && state.link.run;
  if (map.dirty || (state.tab === 'link' && run && run.running) || map.drawing) {
    map.dirty = false;
    draw();
  }
  requestAnimationFrame(frame);
}

function zoom(factor, screenX, screenZ) {
  const blockX = toBlockX(screenX);
  const blockZ = toBlockZ(screenZ);
  map.scale = Math.min(8, Math.max(0.0015, map.scale * factor));
  map.x = blockX - (screenX - map.width / 2) / map.scale;
  map.z = blockZ - (screenZ - map.height / 2) / map.scale;
  map.dirty = true;
}

function fit() {
  const area = areaToShow();
  if (!area || !(area.radius > 0)) return;
  map.fitLater = !(map.width > 0 && map.height > 0);
  if (map.fitLater) return;
  map.x = area.x;
  map.z = area.z;
  map.scale = Math.min(8, Math.max(0.0015, Math.min(map.width, map.height) * 0.42 / area.radius));
  map.dirty = true;
}

function setCenter(blockX, blockZ) {
  const x = Math.round(blockX);
  const z = Math.round(blockZ);
  if (state.tab === 'link') {
    $('link-x').value = x;
    $('link-z').value = z;
    dropPlan();
  } else {
    $('center-x').value = x;
    $('center-z').value = z;
    updateEstimate();
  }
  map.dirty = true;
}

function painting() {
  return state.tab === 'link' && state.plan != null && !(state.link && state.link.run && state.link.run.running);
}

function paintAt(screenX, screenZ) {
  const plan = state.plan;
  const side = plan.tileChunks * CHUNK;
  const tileX = Math.floor(toBlockX(screenX) / side);
  const tileZ = Math.floor(toBlockZ(screenZ) / side);
  const tile = plan.tiles.find(entry => entry[0] === tileX && entry[1] === tileZ);
  if (tile && tile[2] !== state.brush) {
    tile[2] = state.brush;
    map.dirty = true;
    renderBrushes();
  }
}

function showCoords(event) {
  const box = canvas.getBoundingClientRect();
  const x = Math.floor(toBlockX(event.clientX - box.left));
  const z = Math.floor(toBlockZ(event.clientY - box.top));
  let text = `x ${x}  z ${z}  chunk ${x >> 4}, ${z >> 4}`;
  const shown = tilesToShow();
  if (shown) {
    const side = shown.plan.tileChunks * CHUNK;
    const tile = shown.plan.tiles.find(entry => entry[0] === Math.floor(x / side) && entry[1] === Math.floor(z / side));
    if (tile) text += `  tile ${tile[0]}, ${tile[1]}: ${shown.plan.names[tile[2]] ?? 'any machine'}`;
  } else {
    text += `  region ${x >> 9}, ${z >> 9}`;
  }
  $('coords').textContent = text;
}

canvas.addEventListener('contextmenu', event => event.preventDefault());

canvas.addEventListener('pointerdown', event => {
  const box = canvas.getBoundingClientRect();
  const panning = !painting() || event.button !== 0;
  map.pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, moved: false, panning };
  canvas.setPointerCapture(event.pointerId);
  if (!panning) paintAt(event.clientX - box.left, event.clientY - box.top);
});

canvas.addEventListener('pointermove', event => {
  showCoords(event);
  const pointer = map.pointer;
  if (!pointer || pointer.id !== event.pointerId) return;
  const box = canvas.getBoundingClientRect();
  if (!pointer.panning) {
    paintAt(event.clientX - box.left, event.clientY - box.top);
    return;
  }
  const dx = event.clientX - pointer.x;
  const dy = event.clientY - pointer.y;
  if (!pointer.moved && Math.hypot(dx, dy) < 4) return;
  pointer.moved = true;
  map.x -= dx / map.scale;
  map.z -= dy / map.scale;
  pointer.x = event.clientX;
  pointer.y = event.clientY;
  map.dirty = true;
});

canvas.addEventListener('pointerup', event => {
  const pointer = map.pointer;
  map.pointer = null;
  if (!pointer || pointer.moved || !pointer.panning || event.button !== 0) return;
  const box = canvas.getBoundingClientRect();
  if (state.tab === 'server') return;
  setCenter(toBlockX(event.clientX - box.left), toBlockZ(event.clientY - box.top));
});

canvas.addEventListener('pointerleave', () => { $('coords').textContent = ''; });

canvas.addEventListener('wheel', event => {
  event.preventDefault();
  const box = canvas.getBoundingClientRect();
  zoom(event.deltaY < 0 ? 1.2 : 1 / 1.2, event.clientX - box.left, event.clientY - box.top);
}, { passive: false });

$('zoom-in').addEventListener('click', () => zoom(1.4, map.width / 2, map.height / 2));
$('zoom-out').addEventListener('click', () => zoom(1 / 1.4, map.width / 2, map.height / 2));
$('fit').addEventListener('click', fit);
$('terrain').addEventListener('click', () => {
  state.terrain = !state.terrain;
  $('terrain').setAttribute('aria-pressed', String(state.terrain));
  map.dirty = true;
});
new ResizeObserver(resize).observe(canvas);

function renderLegend() {
  const legend = $('legend');
  legend.textContent = '';
  const add = (color, text) => {
    const item = element('span');
    const swatch = element('i', 'swatch');
    swatch.style.setProperty('--swatch', color);
    item.append(swatch, text);
    legend.append(item);
  };
  add(`rgb(${css('--disk')})`, 'On disk');
  if (state.tab === 'pregen') add(`rgb(${css('--saved')})`, 'Saved just now');
  const shown = tilesToShow();
  if (shown) {
    for (const name of shown.plan.names) add(colorOf(name), name);
  }
}

// Tabs ---------------------------------------------------------------------------------------------------------------

function openTab(tab) {
  state.tab = tab;
  for (const name of ['pregen', 'link', 'server']) {
    $('panel-' + name).hidden = name !== tab;
    $('tab-' + name).setAttribute('aria-selected', String(name === tab));
  }
  renderLegend();
  map.dirty = true;
}

document.querySelectorAll('.tab').forEach(button => button.addEventListener('click', () => openTab(button.dataset.tab)));

// Pregen ------------------------------------------------------------------------------------------------------------

function chunksOf(x, z, radius, shape) {
  if (shape === 'circle') return Math.round(Math.PI * radius * radius / 256);
  const span = center => Math.floor((center + radius - 1) / 16) - Math.floor((center - radius) / 16) + 1;
  return span(x) * span(z);
}

function updateEstimate() {
  const size = Number($('size').value);
  const x = Number($('center-x').value);
  const z = Number($('center-z').value);
  const radius = Math.floor(size / 2);
  if (!(radius >= 1) || radius > 50000) {
    $('estimate').textContent = 'The size is 2 to 100,000 blocks.';
    $('start').disabled = true;
    return;
  }
  const chunks = chunksOf(x, z, radius, state.shape);
  $('estimate').textContent = `${number.format(chunks)} chunks, from x ${number.format(x - radius)} to ${number.format(x + radius - 1)}`;
  $('start').disabled = state.running;
}

function setShape(shape) {
  state.shape = shape;
  document.querySelectorAll('.segment').forEach(button => button.setAttribute('aria-checked', String(button.dataset.shape === shape)));
  $('size-label').textContent = shape === 'circle' ? 'Diameter in blocks' : 'Width in blocks';
  updateEstimate();
  map.dirty = true;
}

document.querySelectorAll('.segment').forEach(button => button.addEventListener('click', () => setShape(button.dataset.shape)));

for (const size of [1000, 2000, 5000, 10000, 20000, 50000]) {
  const chip = element('button', 'chip', (size / 1000) + 'k');
  chip.addEventListener('click', () => {
    $('size').value = size;
    updateEstimate();
    fit();
  });
  $('presets').append(chip);
}

for (const id of ['size', 'center-x', 'center-z']) {
  $(id).addEventListener('input', () => {
    updateEstimate();
    map.dirty = true;
  });
}

async function act(action) {
  try {
    const result = await action();
    if (result && result.started === false) toast(result.message || 'It did not start.');
    else if (result && result.message) toast(result.message);
    poll();
    return result;
  } catch (failure) {
    toast(failure.message);
    return null;
  }
}

$('start').addEventListener('click', () => act(() => post('/api/pregen/start', {
  dimension: $('dimension').value,
  shape: state.shape,
  x: $('center-x').value,
  z: $('center-z').value,
  radius: Math.floor(Number($('size').value) / 2),
})));

$('stop').addEventListener('click', () => act(() => post('/api/pregen/stop')));

$('resume').addEventListener('click', () => {
  const status = state.ufp && state.ufp.status;
  if (status) act(() => post('/api/pregen/resume', { dimension: status.dimension }));
});

const STATE_NAMES = { RUNNING: 'Running', COMPLETED: 'Completed', STOPPED: 'Stopped', FAILED: 'Failed' };
const STATE_KINDS = { RUNNING: 'good', COMPLETED: 'good', STOPPED: 'warn', FAILED: 'bad' };

function renderRun(ufp) {
  const status = ufp.status;
  $('run-card').hidden = !status;
  state.running = Boolean(ufp.running);
  updateEstimate();
  if (status) {
    const percent = Math.min(100, status.progress * 100);
    $('run-state').textContent = STATE_NAMES[status.state] || status.state;
    $('run-state').className = 'badge ' + (STATE_KINDS[status.state] || '');
    $('run-bar').style.width = percent + '%';
    $('run-meter').setAttribute('aria-valuenow', Math.round(percent));
    $('run-percent').textContent = percent.toFixed(1) + '%';
    $('run-speed').textContent = number.format(Math.round(status.chunksPerSecond)) + ' chunks/s';
    $('run-eta').textContent = status.ended ? 'None' : duration(status.etaMs);
    $('run-elapsed').textContent = duration(status.elapsedMs);
    $('run-chunks').textContent = number.format(status.done) + ' of ' + number.format(status.total);
    $('run-kept').textContent = number.format(status.kept);
    $('run-world').textContent = worldName(status.dimension);
    $('run-stage').textContent = status.ended ? '' : status.stage || '';
    $('stop').hidden = status.ended;
    $('resume').hidden = !status.ended || status.state === 'COMPLETED';
  }
  const list = $('finished');
  list.textContent = '';
  $('finished-card').hidden = ufp.finished.length === 0;
  for (const run of ufp.finished) {
    const item = element('li', null, `${worldName(run.dimension)}, ${number.format(run.radius * 2)} blocks around ${number.format(run.x)}, ${number.format(run.z)}`);
    item.append(element('small', null, `${STATE_NAMES[run.state] || run.state}: ${number.format(run.done)} chunks in ${duration(run.elapsedMs)}, ${number.format(Math.round(run.chunksPerSecond))} chunks/s`));
    list.append(item);
  }
}

// Link ---------------------------------------------------------------------------------------------------------------

const ROLE_TEXT = {
  host: 'This server hands out tiles and merges the chunks that come back.',
  client: 'This server generates tiles for a host.',
  none: 'Not part of a network yet.',
};

function renderMachines(link) {
  const list = $('machines');
  list.textContent = '';
  for (const machine of link.machines) {
    const item = element('li', 'machine');
    item.style.setProperty('--swatch', colorOf(machine.name));
    const head = element('div', 'machine-head');
    head.append(element('span', null, machine.name), element('span', null, `${machine.threads} threads, ${number.format(machine.heapMaxMb)} MB`));
    item.append(head, element('p', 'work', machine.state));
    const speed = machine.chunksPerSecond > 0 ? `, ${number.format(Math.round(machine.chunksPerSecond))} chunks/s` : '';
    item.append(element('p', null, `${machine.tilesDone} tiles, ${number.format(machine.chunks)} chunks, ${number.format(machine.megabytes)} MB${speed}`));
    item.append(element('p', null, `${machine.address.replace(/^\//, '')}, ${machine.system}`));
    item.append(element('p', null, 'Connected for ' + duration(machine.connectedMs)));
    list.append(item);
  }
  if (link.role === 'host' && link.machines.length === 0) list.append(element('li', 'hint', 'No machine is connected yet.'));
  const refused = $('refused');
  refused.textContent = '';
  $('refused-box').hidden = link.refusals.length === 0;
  for (const entry of link.refusals) {
    const item = element('li', null, `${entry.name} from ${entry.address.replace(/^\//, '')}`);
    item.append(element('small', null, `${new Date(entry.when).toLocaleTimeString()}: ${entry.reason}`));
    refused.append(item);
  }
}

function renderLinkRun(run) {
  $('link-run-card').hidden = !run;
  if (!run) return;
  const total = run.tiles.length;
  const done = run.tiles.filter(tile => tile[3] === 2).length;
  const busy = run.tiles.filter(tile => tile[3] === 1).length;
  const percent = total ? 100 * done / total : 0;
  $('link-run-state').textContent = run.running ? (run.stopReason ? 'Stopping' : 'Running') : done === total ? 'Completed' : 'Stopped';
  $('link-run-state').className = 'badge ' + (run.running ? 'good' : done === total ? 'good' : 'warn');
  $('link-bar').style.width = percent + '%';
  $('link-meter').setAttribute('aria-valuenow', Math.round(percent));
  $('link-percent').textContent = percent.toFixed(1) + '%';
  $('link-tiles').textContent = `${done} of ${total}, ${busy} in progress`;
  $('link-eta').textContent = !run.running ? 'None' : done ? duration(run.elapsedMs * (total - done) / done) : 'Working it out';
  $('link-elapsed').textContent = duration(run.elapsedMs);
  $('link-written').textContent = number.format(run.written);
  $('link-replaced').textContent = number.format(run.replaced);
  $('link-kept').textContent = number.format(run.kept + run.held + run.rejected);
  $('link-waiting').textContent = run.stopReason || (busy === 0 ? run.waitingFor : '') || '';
  $('link-stop').hidden = !run.running;
}

function renderLink(link) {
  const role = link.role || 'none';
  $('link-role').textContent = { host: 'Host', client: 'Client', none: 'No role' }[role];
  $('link-role').className = 'badge ' + (role === 'none' ? 'warn' : 'good');
  $('link-role-text').textContent = ROLE_TEXT[role];
  $('setup-card').hidden = role !== 'none';
  $('invite-card').hidden = role !== 'host';
  $('leave').hidden = role === 'none' || Boolean(link.run && link.run.running);
  $('invite-port').textContent = link.port ? `Other servers reach this one on port ${link.port}: allow it through this machine's firewall.` : '';
  if (role === 'host' && !$('invite-code').value) showInvite();
  if (role !== 'host') $('invite-code').value = '';
  renderMachines(link);
  renderLinkRun(link.run);
  $('plan-card').hidden = role !== 'host' || Boolean(link.run && link.run.running);
  $('local-card').hidden = role === 'client' || Boolean(link.run && link.run.running);
  $('local-activity').textContent = link.activity || '';
  $('client-card').hidden = !link.client;
  if (link.client) {
    const stats = $('client-stats');
    stats.textContent = '';
    const add = (name, value) => {
      const pair = element('div');
      pair.append(element('dt', null, name), element('dd', null, value));
      stats.append(pair);
    };
    add('Name', link.client.name);
    add('Host', `${link.client.host}:${link.client.port}`);
    add('Tiles', number.format(link.client.tilesDone));
    add('Chunks sent', number.format(link.client.chunksSent));
    add('Doing', link.client.state);
  }
  if (state.plan) renderBrushes();
  renderLegend();
}

let askedInvite = false;

async function showInvite() {
  if (askedInvite) return;
  askedInvite = true;
  try {
    const address = $('invite-address').value.trim();
    const { invite } = await post('/api/link/invite', address ? { address } : {});
    $('invite-code').value = invite;
  } catch (failure) {
    toast(failure.message);
  }
  askedInvite = false;
}

$('become-host').addEventListener('click', () => showInvite().then(poll));
$('invite-address').addEventListener('change', showInvite);
$('copy-invite').addEventListener('click', async () => {
  const box = $('invite-code');
  box.select();
  try {
    await navigator.clipboard.writeText(box.value);
  } catch {
    // Browsers keep the clipboard API to https and this machine; the line is selected for Ctrl+C.
    document.execCommand('copy');
  }
  toast('Copied.');
});
$('join').addEventListener('click', () => act(async () => {
  const result = await post('/api/link/join', { invite: $('join-code').value });
  if (result.started) $('join-code').value = '';
  return result;
}));
$('leave').addEventListener('click', () => act(() => post('/api/link/leave')));

function dropPlan() {
  state.plan = null;
  $('plan-box').hidden = true;
  $('plan-hint').hidden = false;
  map.dirty = true;
  renderLegend();
}

function linkArea() {
  return { dimension: $('dimension').value, x: $('link-x').value, z: $('link-z').value, sizeK: $('link-size').value };
}

$('make-plan').addEventListener('click', async () => {
  try {
    state.plan = await post('/api/link/plan', linkArea());
    state.brush = state.plan.names.length ? 0 : -1;
    $('plan-box').hidden = false;
    $('plan-hint').hidden = true;
    renderBrushes();
    renderLegend();
    fit();
  } catch (failure) {
    toast(failure.message);
  }
});

function renderBrushes() {
  const plan = state.plan;
  const list = $('brushes');
  const connected = new Map((state.link ? state.link.machines : []).map(machine => [machine.name, machine]));
  for (const name of connected.keys()) {
    if (!plan.names.includes(name)) plan.names.push(name);
  }
  const counts = new Array(plan.names.length).fill(0);
  let open = 0;
  for (const tile of plan.tiles) {
    if (tile[2] >= 0) counts[tile[2]]++;
    else open++;
  }
  const chunksPerTile = plan.tileChunks * plan.tileChunks;
  const brush = (index, name, count, color, away) => {
    const item = element('li');
    const button = element('button', 'brush');
    button.setAttribute('aria-pressed', String(state.brush === index));
    const swatch = element('i', 'swatch');
    swatch.style.setProperty('--swatch', color);
    const machine = connected.get(name);
    const time = machine && machine.chunksPerSecond > 0 ? `, ${duration(1000 * count * chunksPerTile / machine.chunksPerSecond)}` : '';
    button.append(swatch, element('span', null, away ? `${name} (not connected)` : name),
      element('small', null, `${count} tiles, ${number.format(count * chunksPerTile)} chunks${time}`));
    button.addEventListener('click', () => {
      state.brush = index;
      renderBrushes();
    });
    item.append(button);
    return item;
  };
  const items = plan.names.map((name, index) => brush(index, name, counts[index], colorOf(name), !connected.has(name)));
  items.push(brush(-1, 'Any machine', open, css('--map-axis'), false));
  list.replaceChildren(...items);
}

$('plan-clear').addEventListener('click', dropPlan);

$('plan-start').addEventListener('click', () => act(async () => {
  const plan = state.plan;
  const result = await post('/api/link/start', {
    ...linkArea(),
    name: plan.names,
    assign: plan.tiles.map(tile => `${tile[0]},${tile[1]},${tile[2]}`).join(';'),
    helpOthers: String($('help-others').checked),
  });
  if (result.started) dropPlan();
  return result;
}));

for (const id of ['link-x', 'link-z', 'link-size']) {
  $(id).addEventListener('input', () => {
    dropPlan();
    map.dirty = true;
  });
}

$('link-stop').addEventListener('click', () => act(() => post('/api/link/stop')));
$('local-stop').addEventListener('click', () => act(() => post('/api/link/stop')));
$('local-start').addEventListener('click', () => act(() => post('/api/link/local', linkArea())));
$('local-resume').addEventListener('click', () => act(() => post('/api/link/resume')));

// Server -------------------------------------------------------------------------------------------------------------

const SETTING_KEYS = ['batch_size', 'threads', 'parallel_features', 'terrain_pipeline'];
const SETTING_FLAGS = ['skip_existing_chunks', 'resume_after_restart', 'release_memory_each_batch'];

async function loadSettings() {
  const values = await get('/api/settings');
  for (const key of SETTING_KEYS) $('set-' + key).value = values[key];
  for (const key of SETTING_FLAGS) $('set-' + key).checked = values[key] === 'true';
}

$('save-settings').addEventListener('click', async () => {
  const fields = {};
  for (const key of SETTING_KEYS) fields[key] = $('set-' + key).value;
  for (const key of SETTING_FLAGS) fields[key] = String($('set-' + key).checked);
  try {
    await post('/api/settings', fields);
    toast('Saved. The next run uses these settings.');
  } catch (failure) {
    toast(failure.message);
  }
});

function renderServer(poll) {
  const info = state.info;
  const stats = $('server-stats');
  stats.textContent = '';
  const add = (name, value) => {
    if (value == null) return;
    const pair = element('div');
    pair.append(element('dt', null, name), element('dd', null, value));
    stats.append(pair);
  };
  add('Manager', info.manager);
  if (info.ufp) add('Ultra Fast Pregen', info.ufp.version);
  if (info.link) add('Link', info.link.version);
  add('World folder', info.world);
  add('CPU threads', String(info.cpus));
  add('Heap in use', `${number.format(poll.heapUsedMb)} of ${number.format(info.heapMaxMb)} MB`);
  add('System', info.system);
  add('Java', info.java);
}

$('forget').addEventListener('click', () => {
  saveToken(null);
  state.token = null;
  askToken(false);
});

// Polling ------------------------------------------------------------------------------------------------------------

function setHealth(online, text) {
  $('dot').className = 'dot ' + (online ? 'on' : 'off');
  $('health-text').textContent = text;
}

let pollTimer;

async function poll() {
  clearTimeout(pollTimer);
  if (!state.token) return;
  try {
    const answer = await get('/api/poll' + (state.since != null ? '?since=' + state.since : ''));
    setHealth(true, state.info ? `${state.info.world || 'Singleplayer'}, ${number.format(answer.heapUsedMb)} MB in use` : 'Connected');
    if (answer.ufp) {
      state.ufp = answer.ufp;
      state.since = answer.ufp.sequence;
      for (const batch of answer.ufp.batches) {
        if (batch.dimension !== $('dimension').value) continue;
        for (let i = 0; i < batch.chunks.length; i += 2) markSaved(batch.chunks[i], batch.chunks[i + 1]);
      }
      renderRun(answer.ufp);
    }
    if (answer.link) {
      state.link = answer.link;
      renderLink(answer.link);
    }
    renderServer(answer);
    if (Date.now() - state.lastRegions > 20000) loadRegions().catch(() => {});
    map.dirty = true;
    pollTimer = setTimeout(poll, state.running || (state.link && state.link.run && state.link.run.running) ? 1000 : 2500);
  } catch (failure) {
    if (failure.status === 401) {
      askToken(true);
      return;
    }
    setHealth(false, 'No answer from the server');
    pollTimer = setTimeout(poll, 3000);
  }
}

function askToken(wrong) {
  clearTimeout(pollTimer);
  $('login-error').hidden = !wrong;
  $('token').value = '';
  if (!$('login').open) $('login').showModal();
  $('token').focus();
}

// A code from /ufpmanager, traded for the token. Null when it was refused.
async function login(code) {
  const response = await fetch('/api/login', { method: 'POST', body: new URLSearchParams({ code }) }).catch(() => null);
  return response && response.ok ? (await response.json()).token : null;
}

$('login-form').addEventListener('submit', async event => {
  event.preventDefault();
  const typed = $('token').value.trim();
  const token = typed.length > 12 ? typed : await login(typed);
  if (!token) {
    askToken(true);
    return;
  }
  state.token = token;
  $('login').close();
  begin();
});

async function begin() {
  try {
    state.info = await get('/api/info');
  } catch (failure) {
    if (failure.status === 401) {
      askToken(true);
      return;
    }
    setHealth(false, 'No answer from the server');
    setTimeout(begin, 3000);
    return;
  }
  saveToken(state.token);
  $('tab-pregen').hidden = !state.info.ufp;
  $('tab-link').hidden = !state.info.link;
  $('settings-card').hidden = !state.info.ufp;
  const { dimensions } = await get('/api/dimensions');
  const select = $('dimension');
  select.textContent = '';
  for (const id of dimensions) {
    const option = element('option', null, worldName(id));
    option.value = id;
    select.append(option);
  }
  if (state.info.ufp) loadSettings().catch(failure => toast(failure.message));
  openTab(state.info.ufp ? 'pregen' : state.info.link ? 'link' : 'server');
  state.noMods = !state.info.ufp && !state.info.link;
  if (state.noMods) $('map-note').textContent = 'Neither Ultra Fast Pregen nor Ultra Fast Pregen Link is installed.';
  setShape('square');
  resize();
  fit();
  loadRegions().catch(failure => toast(failure.message));
  poll();
}

$('dimension').addEventListener('change', () => {
  clearRegions();
  state.lastRegions = 0;
  dropPlan();
  loadRegions().catch(failure => toast(failure.message));
});

function fromLink(name) {
  const found = new RegExp(`[#&]${name}=([^&]+)`).exec(location.hash);
  if (!found) return null;
  history.replaceState(null, '', location.pathname);
  return decodeURIComponent(found[1]);
}

async function openPage() {
  const code = fromLink('code');
  state.token = fromLink('token') || (code && await login(code)) || loadToken();
  if (state.token) begin();
  else askToken(Boolean(code));
}

resize();
requestAnimationFrame(frame);
openPage();
