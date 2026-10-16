/* MediBridge front end - plain JavaScript, no build step.
 *
 * Safety note: every piece of server data is inserted with textContent / createTextNode (via h()),
 * never innerHTML, so a medicine or outlet name can't inject HTML or scripts.
 */
'use strict';

// ------------------------------------------------------------------ helpers

const $ = (sel, root = document) => root.querySelector(sel);

/** Tiny DOM builder: h('div', {class: 'x', onclick: fn}, 'text', childNode, ...) */
function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

async function api(path, { method = 'GET', body, auth = false } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth) {
    const t = sessionStorage.getItem('mb_token');
    if (t) headers['Authorization'] = 'Bearer ' + t;
  }
  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new Error('Cannot reach the MediBridge server. Is it still running?');
  }
  let data = null;
  try { data = await res.json(); } catch (e) { /* no body */ }
  if (!res.ok) {
    const err = new Error((data && data.error) || res.statusText || 'Request failed');
    err.status = res.status;
    throw err;
  }
  return data;
}

let toastTimer;
function toast(msg, isError = false) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.toggle('err', isError);
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3800);
}

function ago(minutes) {
  if (minutes < 1) return 'just now';
  if (minutes < 60) return minutes + ' min ago';
  const hrs = Math.floor(minutes / 60);
  if (hrs < 24) return hrs + (hrs === 1 ? ' hour ago' : ' hours ago');
  const days = Math.floor(hrs / 24);
  return days + (days === 1 ? ' day ago' : ' days ago');
}

const STATUS_TEXT = { AVAILABLE: '\u2713 Available', LOW: '! Low stock', OUT: '\u2715 Out of stock' };
const FRESH_TEXT = { FRESH: 'Fresh', RECENT: 'Recent', AGING: 'Aging', STALE: 'Stale' };

// ------------------------------------------------------------------ state

let config = null;
let userLoc = null; // null = demo location, else {lat, lng}

function loadAlertKeys() {
  try { return JSON.parse(localStorage.getItem('mb_alert_keys') || '[]'); } catch (e) { return []; }
}
function saveAlertKey(key) {
  const keys = loadAlertKeys();
  if (!keys.includes(key)) keys.push(key);
  try { localStorage.setItem('mb_alert_keys', JSON.stringify(keys)); } catch (e) { /* private mode */ }
}
function removeAlertKey(key) {
  try { localStorage.setItem('mb_alert_keys', JSON.stringify(loadAlertKeys().filter(k => k !== key))); } catch (e) { /* ignore */ }
}

// ------------------------------------------------------------------ tabs

const TABS = ['find', 'alerts', 'outlet', 'impact'];

function showTab() {
  let name = window.location.hash.replace('#', '');
  if (!TABS.includes(name)) name = 'find';
  for (const t of TABS) $('#tab-' + t).hidden = (t !== name);
  document.querySelectorAll('.tabs a').forEach(a => {
    const on = a.dataset.tab === name;
    a.classList.toggle('active', on);
    if (on) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  if (name === 'alerts') refreshAlerts();
  if (name === 'impact') refreshImpact();
  if (name === 'outlet') refreshOutlet();
}

// ------------------------------------------------------------------ find medicine

async function initFind() {
  // Wire up the controls first, so the page works even before the lists below have loaded.
  $('#search-form').addEventListener('submit', e => { e.preventDefault(); runSearch(); });

  $('#loc-btn').addEventListener('click', () => {
    if (!navigator.geolocation) { toast('Your browser cannot share its location.', true); return; }
    $('#loc-status').textContent = 'Finding your location\u2026';
    navigator.geolocation.getCurrentPosition(pos => {
      userLoc = { lat: pos.coords.latitude, lng: pos.coords.longitude };
      $('#loc-status').textContent = 'Using your current location.';
      $('#loc-btn').hidden = true;
      $('#loc-demo').hidden = false;
      if ($('#q').value.trim().length >= 2) runSearch();
    }, () => {
      $('#loc-status').textContent = 'Using the demo location.';
      toast('Could not get your location. Staying on the demo location.', true);
    }, { timeout: 10000 });
  });

  $('#loc-demo').addEventListener('click', () => {
    userLoc = null;
    $('#loc-status').textContent = 'Using the demo location.';
    $('#loc-btn').hidden = false;
    $('#loc-demo').hidden = true;
    if ($('#q').value.trim().length >= 2) runSearch();
  });

  // Then fill the dropdown / suggestions.
  config = await api('/api/config');
  const formSel = $('#form-filter');
  for (const f of config.forms) formSel.append(h('option', { value: f }, f));

  const meds = await api('/api/medicines');
  const dl = $('#med-suggestions');
  const seen = new Set();
  for (const m of meds) {
    if (seen.has(m.name)) continue;
    seen.add(m.name);
    dl.append(h('option', { value: m.name }));
  }
}

async function runSearch() {
  const q = $('#q').value.trim();
  if (q.length < 2) return;
  const params = new URLSearchParams({ q, radius: $('#radius').value });
  if ($('#form-filter').value) params.set('form', $('#form-filter').value);
  if (userLoc) { params.set('lat', userLoc.lat); params.set('lng', userLoc.lng); }

  const box = clear($('#results'));
  box.append(h('p', { class: 'muted' }, 'Searching\u2026'));
  try {
    const data = await api('/api/search?' + params.toString());
    renderResults(data);
  } catch (e) {
    clear(box).append(h('div', { class: 'empty' }, e.message));
  }
}

function renderResults(data) {
  const box = clear($('#results'));
  if (data.groups.length === 0) {
    box.append(h('div', { class: 'empty' },
      'No medicine in the catalogue matches \u201C' + data.query + '\u201D. Check the spelling or try a shorter name.'));
    return;
  }
  for (const g of data.groups) {
    const inStock = g.results.filter(r => r.status !== 'OUT').length;
    const group = h('section', { class: 'med-group' },
      h('div', { class: 'med-title' },
        h('h2', null, g.medicine.label),
        h('small', null, g.results.length === 0
          ? 'No participating outlet within ' + data.radiusKm + ' km'
          : inStock + ' of ' + g.results.length + ' nearby outlets report stock')));

    if (g.results.length === 0) {
      group.append(h('div', { class: 'empty' },
        'No outlet near you has reported this medicine. Try a wider radius' +
        (userLoc ? ', or switch to the demo location (the demo outlets are all in one area).' : '.')));
    }
    g.results.forEach((r, i) => group.append(resultCard(r, i === 0 && r.status !== 'OUT')));

    if (inStock === 0) group.append(notifyBox(g.medicine));
    box.append(group);
  }
}

function resultCard(r, isBest) {
  const phoneOk = /^\+?[0-9][0-9 \-]{6,17}$/.test(r.phone);
  const bars = h('div', { class: 'bars' });
  for (const [k, label] of [['availability', 'Availability'], ['freshness', 'Freshness'],
                            ['verification', 'Verification'], ['distance', 'Distance']]) {
    const fill = h('span');
    fill.style.width = r.breakdown[k] + '%';
    bars.append(h('div', null, label), h('div', { class: 'bar' }, fill), h('div', { class: 'val' }, r.breakdown[k]));
  }

  return h('article', { class: 'result' + (r.status === 'OUT' ? ' is-out' : '') },
    h('div', { class: 'r-top' },
      h('div', null,
        h('div', { class: 'r-name' }, r.name),
        h('div', { class: 'r-sub' }, r.address),
        h('div', { class: 'badges' },
          isBest ? h('span', { class: 'chip', title: 'Highest-ranked report with stock' }, '\u2605 Top match') : null,
          h('span', { class: 'chip type' }, r.type === 'HOSPITAL' ? 'Hospital pharmacy' : 'Retail pharmacy'),
          r.verified ? h('span', { class: 'chip verified' }, '\u2713 Verified outlet') : null)),
      h('span', { class: 'pill ' + r.status }, STATUS_TEXT[r.status])),
    h('div', { class: 'r-meta' },
      h('span', null, 'Updated ', h('strong', null, ago(r.ageMinutes)), ' ',
        h('span', { class: 'chip ' + r.freshness }, FRESH_TEXT[r.freshness])),
      h('span', null, h('strong', null, r.distanceKm.toFixed(1) + ' km'), ' away'),
      h('span', { class: 'muted' }, 'Rank score ' + r.score + '/100')),
    r.freshness === 'STALE' || r.freshness === 'AGING'
      ? h('div', { class: 'stale-note' }, '\u26A0 This report is old. Call the outlet to confirm before you travel.')
      : null,
    h('div', { class: 'r-actions' },
      h('a', { class: 'btn primary small', href: r.directionsUrl, target: '_blank', rel: 'noopener noreferrer' }, 'Directions'),
      phoneOk ? h('a', { class: 'btn small', href: 'tel:' + r.phone.replace(/[^0-9+]/g, '') }, 'Call ' + r.phone) : null),
    h('details', { class: 'why' }, h('summary', null, 'Why this rank?'), bars));
}

function notifyBox(medicine) {
  const input = h('input', { type: 'text', id: 'contact-' + medicine.id, maxlength: 100,
                              placeholder: 'e-mail or phone number', autocomplete: 'email' });
  const btn = h('button', { class: 'btn primary small', type: 'button' }, 'Notify me');
  btn.addEventListener('click', async () => {
    try {
      const a = await api('/api/alerts', { method: 'POST', body: { medicineId: medicine.id, contact: input.value } });
      saveAlertKey(a.key);
      input.value = '';
      toast('Alert created. You will find it under \u201CMy alerts\u201D.');
    } catch (e) { toast(e.message, true); }
  });
  return h('div', { class: 'result' },
    h('strong', null, 'Nothing in stock nearby right now.'),
    h('div', { class: 'r-sub' }, 'Get an alert when any participating outlet reports ' + medicine.label + ' as available.'),
    h('div', { class: 'notify' },
      h('div', { class: 'field' }, h('label', { for: 'contact-' + medicine.id }, 'Where should we reach you?'), input),
      btn));
}

// ------------------------------------------------------------------ alerts

async function refreshAlerts() {
  const box = clear($('#alerts-list'));
  const keys = loadAlertKeys();
  if (keys.length === 0) {
    box.append(h('div', { class: 'empty' }, 'No alerts yet. Search for a medicine that is out of stock and choose \u201CNotify me\u201D.'));
    return;
  }
  try {
    const alerts = await api('/api/alerts?keys=' + encodeURIComponent(keys.join(',')));
    if (alerts.length === 0) {
      box.append(h('div', { class: 'empty' }, 'No alerts found for this browser.'));
      return;
    }
    // Waiting alerts first, then triggered, then cancelled.
    const order = { TRIGGERED: 0, WAITING: 1, CANCELLED: 2 };
    alerts.sort((a, b) => order[a.status] - order[b.status]);
    for (const a of alerts) box.append(alertCard(a));
  } catch (e) {
    box.append(h('div', { class: 'empty' }, e.message));
  }
}

function alertCard(a) {
  let line;
  if (a.status === 'TRIGGERED') line = '\u2713 Now available at ' + a.triggeredBy + ' (reported ' + ago(a.triggeredAgoMinutes) + ')';
  else if (a.status === 'WAITING') line = 'Waiting for an outlet to report stock. Created ' + ago(a.ageMinutes) + '.';
  else line = 'Cancelled';
  const actions = h('div');
  if (a.status === 'WAITING') {
    actions.append(h('button', { class: 'btn small', type: 'button', onclick: async () => {
      try {
        await api('/api/alerts/cancel', { method: 'POST', body: { key: a.key } });
        refreshAlerts();
      } catch (e) { toast(e.message, true); }
    } }, 'Cancel alert'));
  } else {
    actions.append(h('button', { class: 'btn small ghost', type: 'button', onclick: () => {
      removeAlertKey(a.key);
      refreshAlerts();
    } }, 'Remove'));
  }
  return h('div', { class: 'card alert-item ' + a.status.toLowerCase() },
    h('div', null, h('h3', null, a.medicine), h('div', { class: 'muted' }, line),
      h('div', { class: 'r-sub' }, 'Contact: ' + a.contact)),
    actions);
}

// ------------------------------------------------------------------ impact

async function refreshImpact() {
  try {
    const m = await api('/api/metrics');
    const box = clear($('#metrics'));
    const tiles = [
      [m.activeOutlets + ' / ' + m.outlets, 'outlets online'],
      [m.liveListings, 'live stock reports'],
      [m.freshListingsPct + '%', 'reports updated in the last hour'],
      [m.searches, 'searches made'],
      [m.matchRatePct + '%', 'searches that found stock (match rate)'],
      [m.alertsCreated, 'alerts created'],
      [m.alertsTriggered, 'alerts fulfilled'],
    ];
    for (const [n, l] of tiles) box.append(h('div', { class: 'metric' }, h('div', { class: 'n' }, n), h('div', { class: 'l' }, l)));

    if (!config) config = await api('/api/config');
    const w = clear($('#weights'));
    const colours = { availability: '#0f766e', freshness: '#1d5fa8', verification: '#6d4bb5', distance: '#a15c07' };
    for (const k of ['availability', 'freshness', 'verification', 'distance']) {
      const seg = h('div', null, k[0].toUpperCase() + k.slice(1) + ' ' + config.weights[k] + '%');
      seg.style.flex = String(config.weights[k]);
      seg.style.background = colours[k];
      w.append(seg);
    }
  } catch (e) { toast(e.message, true); }
}

// ------------------------------------------------------------------ outlet portal

function loggedIn() { return !!sessionStorage.getItem('mb_token'); }

function initOutlet() {
  $('#login-form').addEventListener('submit', async e => {
    e.preventDefault();
    const err = $('#login-error');
    err.hidden = true;
    try {
      const res = await api('/api/outlet/login', { method: 'POST',
        body: { code: $('#code').value, password: $('#password').value } });
      sessionStorage.setItem('mb_token', res.token);
      $('#password').value = '';
      refreshOutlet();
    } catch (ex) {
      err.textContent = ex.message;
      err.hidden = false;
    }
  });

  $('#logout-btn').addEventListener('click', async () => {
    try { await api('/api/outlet/logout', { method: 'POST', auth: true, body: {} }); } catch (e) { /* ignore */ }
    sessionStorage.removeItem('mb_token');
    refreshOutlet();
  });

  $('#add-form').addEventListener('submit', async e => {
    e.preventDefault();
    const id = Number($('#add-med').value);
    if (!id) return;
    await postOutlet('/api/outlet/stock', { medicineId: id, status: $('#add-status').value });
  });
}

/** Sends a change from the dashboard, then redraws it. */
async function postOutlet(path, body) {
  try {
    const res = await api(path, { method: 'POST', auth: true, body });
    toast(res.alertsTriggered > 0
      ? 'Saved. ' + res.alertsTriggered + ' waiting alert(s) notified.'
      : 'Saved.');
    await refreshOutlet();
  } catch (e) {
    if (e.status === 401) { sessionStorage.removeItem('mb_token'); refreshOutlet(); }
    toast(e.message, true);
  }
}

async function refreshOutlet() {
  const login = $('#outlet-login');
  const dash = $('#outlet-dash');
  if (!loggedIn()) { login.hidden = false; dash.hidden = true; return; }
  try {
    const me = await api('/api/outlet/me', { auth: true });
    login.hidden = true;
    dash.hidden = false;
    renderDashboard(me);
    const audit = await api('/api/outlet/audit', { auth: true });
    const list = clear($('#audit-list'));
    if (audit.length === 0) list.append(h('li', { class: 'muted' }, 'No changes yet.'));
    for (const a of audit) {
      list.append(h('li', null, h('time', null, ago(a.ageMinutes)), h('span', { class: 'act' }, a.action), h('span', null, a.detail)));
    }
  } catch (e) {
    if (e.status === 401) {
      sessionStorage.removeItem('mb_token');
      login.hidden = false;
      dash.hidden = true;
    } else {
      toast(e.message, true);
    }
  }
}

function renderDashboard(me) {
  const o = me.outlet;
  $('#dash-name').textContent = o.name;
  $('#dash-meta').textContent = (o.type === 'HOSPITAL' ? 'Hospital pharmacy' : 'Retail pharmacy') +
    ' \u00B7 code ' + o.code + (o.verified ? ' \u00B7 verified' : '');
  const state = $('#dash-state');
  state.textContent = o.paused ? 'PAUSED' : 'ONLINE';
  state.className = 'pill ' + (o.paused ? 'PAUSED' : 'ONLINE');
  const pauseBtn = $('#pause-btn');
  pauseBtn.textContent = o.paused ? 'Resume outlet' : 'Pause outlet';
  pauseBtn.onclick = () => postOutlet('/api/outlet/pause', { paused: !o.paused });

  const body = clear($('#stock-body'));
  if (me.stock.length === 0) body.append(h('tr', null, h('td', { colspan: 5, class: 'muted' }, 'No stock reports yet. Add one below.')));
  for (const row of me.stock) {
    const sel = h('select', { 'aria-label': 'Status of ' + row.label },
      h('option', { value: 'AVAILABLE' }, 'Available'),
      h('option', { value: 'LOW' }, 'Low stock'),
      h('option', { value: 'OUT' }, 'Out of stock'));
    sel.value = row.status;
    sel.addEventListener('change', () => postOutlet('/api/outlet/stock', { medicineId: row.medicineId, status: sel.value }));

    const vis = h('input', { type: 'checkbox', 'aria-label': 'Show ' + row.label + ' to the public' });
    vis.checked = row.visible;
    vis.addEventListener('change', () => postOutlet('/api/outlet/visibility', { medicineId: row.medicineId, visible: vis.checked }));

    body.append(h('tr', null,
      h('td', null, h('strong', null, row.label)),
      h('td', null, sel),
      h('td', null, ago(row.ageMinutes)),
      h('td', null, h('label', { class: 'check' }, vis, row.visible ? 'Shown' : 'Hidden')),
      h('td', { class: 'muted' }, row.searches + ' searches \u00B7 ' + row.alertsWaiting + ' waiting')));
  }

  const add = clear($('#add-med'));
  if (me.unlisted.length === 0) {
    add.append(h('option', { value: '' }, 'All medicines are already reported'));
  } else {
    for (const m of me.unlisted) add.append(h('option', { value: m.id }, m.label));
  }
}

// ------------------------------------------------------------------ start

function initTheme() {
  const toggle = $('#theme-toggle');
  let theme = 'light';
  try {
    if (localStorage.getItem('mb_theme') === 'dark') theme = 'dark';
  } catch (e) {
    // The toggle remains available when browser storage is disabled.
  }

  const applyTheme = () => {
    const isDark = theme === 'dark';
    document.documentElement.dataset.theme = theme;
    toggle.setAttribute('aria-pressed', String(isDark));
    toggle.textContent = isDark ? 'Light theme' : 'Dark theme';
  };

  toggle.addEventListener('click', () => {
    theme = theme === 'dark' ? 'light' : 'dark';
    applyTheme();
    try {
      localStorage.setItem('mb_theme', theme);
    } catch (e) {
      toast('Theme changed, but this browser could not save your preference.', true);
    }
  });
  applyTheme();
}

window.addEventListener('hashchange', showTab);
window.addEventListener('DOMContentLoaded', async () => {
  initTheme();
  initOutlet();
  showTab();
  try {
    await initFind();
  } catch (e) {
    toast(e.message, true);
  }
});
