/* RD-Bot panel. No frameworks: fetch + DOM. CSRF via X-CSRF-Token header. */
'use strict';
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const csrf = () => (document.cookie.split('; ').find((c) => c.startsWith('rdbot_csrf=')) || '=').split('=')[1] || '';
let guild = '';

async function api(path, method, body) {
  const opts = { method: method || 'GET', headers: { 'X-CSRF-Token': csrf() } };
  if (body !== undefined) {
    opts.headers['Content-Type'] = 'application/json';
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  if (res.status === 401 || res.status === 403) { location.reload(); throw new Error('auth'); }
  const json = await res.json();
  if (!res.ok) throw new Error((json && json.error) || ('HTTP ' + res.status));
  return json;
}
const gq = (extra) => (extra ? '&' + extra : '');
const fmtTime = (ms) => new Date(ms).toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

async function boot() {
  let data;
  try {
    data = await api('/api/guilds');
  } catch (e) {
    $('boot-msg').textContent = 'Auth failed. Open the panel with a valid ?token= link.';
    return;
  }
  const sel = $('guild');
  sel.innerHTML = '';
  (data.guilds || []).forEach((g) => {
    const o = document.createElement('option');
    o.value = g.id; o.textContent = g.name; sel.appendChild(o);
  });
  if (!data.guilds || !data.guilds.length) {
    const o = document.createElement('option');
    o.value = 'global'; o.textContent = '(no servers yet — global settings)'; sel.appendChild(o);
  }
  guild = sel.value;
  $('boot').classList.add('hidden');
  $('app').classList.remove('hidden');
  sel.addEventListener('change', () => { guild = sel.value; refreshAll(); });
  wireNav(); wireKb(); wireInsights(); wireEscalations(); wireHours(); wirePersonality(); wirePrompt(); wireKeys(); wireTest(); wireLogout();
  refreshAll();
}

function refreshAll() {
  loadKnowledge('custom'); loadChannels(); loadInsights(); loadEscalations(); loadEscSettings(); loadHours(); loadPersonality(); loadPrompt(); loadKeys();
}

function wireNav() {
  document.querySelectorAll('#nav button').forEach((b) => b.addEventListener('click', () => {
    document.querySelectorAll('#nav button').forEach((x) => x.classList.remove('active'));
    b.classList.add('active');
    document.querySelectorAll('.page').forEach((p) => p.classList.add('hidden'));
    $('page-' + b.dataset.page).classList.remove('hidden');
  }));
  document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => {
    document.querySelectorAll('.tabs button').forEach((x) => x.classList.remove('active'));
    b.classList.add('active');
    $('esc-settings').classList.toggle('hidden', b.dataset.tab !== 'settings');
    $('esc-recent').classList.toggle('hidden', b.dataset.tab !== 'recent');
    if (b.dataset.tab === 'recent') loadEscalations();
  }));
  document.querySelectorAll('.kb-nav button').forEach((b) => b.addEventListener('click', () => {
    document.querySelectorAll('.kb-nav button').forEach((x) => x.classList.remove('active'));
    b.classList.add('active');
    ['custom', 'web', 'discord', 'document'].forEach((k) => $('kb-' + k).classList.toggle('hidden', k !== b.dataset.kind));
    loadKnowledge(b.dataset.kind);
  }));
}

function counter(inputId, countId, max) {
  $(inputId).addEventListener('input', () => { $(countId).textContent = $(inputId).value.length + '/' + max; });
}

/* ---------------- knowledge ---------------- */
let kbKind = 'custom';
function wireKb() {
  counter('kb-topic', 'topic-count', 200); counter('kb-info', 'info-count', 5000);
  $('kb-add').addEventListener('click', async () => {
    await api('/api/knowledge', 'POST', { guild, topic: $('kb-topic').value, information: $('kb-info').value });
    $('kb-topic').value = ''; $('kb-info').value = '';
    loadKnowledge('custom');
  });
  $('kb-export').addEventListener('click', async () => {
    const data = await api('/api/knowledge/export?guild=' + guild);
    const blob = new Blob([JSON.stringify(data.items, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = 'knowledge.json'; a.click();
  });
  $('kb-import-btn').addEventListener('click', () => $('kb-import-file').click());
  $('kb-import-file').addEventListener('change', async () => {
    const f = $('kb-import-file').files[0]; if (!f) return;
    const items = JSON.parse(await f.text());
    const r = await api('/api/knowledge/import', 'POST', { guild, items });
    alert('Imported ' + r.added);
    loadKnowledge('custom');
  });
  $('web-add').addEventListener('click', async () => {
    await api('/api/sources/web', 'POST', { guild, url: $('web-url').value });
    $('web-url').value = ''; loadKnowledge('web');
  });
  $('discord-add').addEventListener('click', async () => {
    const sel = $('discord-channel');
    await api('/api/sources/discord', 'POST', { guild, channelId: sel.value, name: sel.selectedOptions[0].textContent });
    loadKnowledge('discord');
  });
  $('discord-sync').addEventListener('click', async () => {
    const items = (await api('/api/knowledge?guild=' + guild + '&kind=discord')).items;
    for (const it of items) await api('/api/sources/discord/sync', 'POST', { guild, id: it.id });
    loadKnowledge('discord');
  });
  $('doc-add').addEventListener('click', async () => {
    const f = $('doc-file').files[0]; if (!f) return;
    const buf = await f.arrayBuffer();
    let bin = ''; const bytes = new Uint8Array(buf);
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    await api('/api/documents', 'POST', { guild, name: f.name, data: btoa(bin) });
    loadKnowledge('document');
  });
}

async function loadKnowledge(kind) {
  kbKind = kind;
  const data = await api('/api/knowledge?guild=' + guild + '&kind=' + kind);
  const limits = data.limits || {}, usage = data.usage || {};
  $('limits').innerHTML = ['custom', 'web', 'discord', 'document'].map((k) => {
    const label = { custom: 'Custom Knowledge', web: 'Web Sources', discord: 'Discord Sources', document: 'Files' }[k];
    const u = usage[k] || 0, l = limits[k] || 0;
    const pct = l ? Math.min(100, Math.round((u / l) * 100)) : 0;
    return '<div>' + label + '<div class="bar"><div style="width:' + pct + '%"></div><span>' + u + ' / ' + l + '</span></div></div>';
  }).join('');
  const host = { custom: $('kb-items'), web: $('web-items'), discord: $('discord-items'), document: $('doc-items') }[kind];
  if (kind === 'custom') $('kb-items-title').textContent = 'Knowledge Items (' + data.items.length + ')';
  if (!data.items.length) {
    host.innerHTML = kind === 'custom'
      ? '<div class="item"><b>You haven\'t added any custom knowledge yet. Start by adding one above!</b></div>'
      : '<div class="item"><b>No sources yet.</b></div>';
    return;
  }
  host.innerHTML = '';
  data.items.forEach((it) => {
    const div = document.createElement('div');
    div.className = 'item';
    div.innerHTML = '<b>#' + it.id + ' ' + esc(it.topic) + '</b><div class="meta">' + esc(it.url || '') + ' ' + esc(it.content || '').slice(0, 220) + '</div><div class="actions"></div>';
    const actions = div.querySelector('.actions');
    if (kind === 'custom') {
      const edit = document.createElement('button'); edit.className = 'ghost'; edit.textContent = 'Edit';
      edit.addEventListener('click', async () => {
        const topic = prompt('Topic', it.topic); if (topic === null) return;
        await api('/api/knowledge', 'PUT', { id: it.id, topic, information: it.content });
        loadKnowledge('custom');
      });
      const del = document.createElement('button'); del.className = 'danger'; del.textContent = 'Delete';
      del.addEventListener('click', async () => {
        if (!confirm('Delete #' + it.id + '?')) return;
        await api('/api/knowledge?id=' + it.id, 'DELETE', {});
        loadKnowledge('custom');
      });
      actions.append(edit, del);
    }
    if (kind === 'web') {
      const sync = document.createElement('button'); sync.className = 'ghost'; sync.textContent = 'Re-sync';
      sync.addEventListener('click', async () => { await api('/api/sources/web/resync', 'POST', { id: it.id }); loadKnowledge('web'); });
      const del = document.createElement('button'); del.className = 'danger'; del.textContent = 'Delete';
      del.addEventListener('click', async () => { await api('/api/knowledge?id=' + it.id, 'DELETE', {}); loadKnowledge('web'); });
      actions.append(sync, del);
    }
    host.appendChild(div);
  });
}

async function loadChannels() {
  try {
    const data = await api('/api/channels?guild=' + guild);
    const sel = $('discord-channel');
    sel.innerHTML = '';
    data.channels.forEach((c) => {
      const o = document.createElement('option');
      o.value = c.id; o.textContent = c.name; sel.appendChild(o);
    });
  } catch (e) { /* bot may be offline */ }
}

/* ---------------- insights ---------------- */
function wireInsights() {
  $('insights-refresh').addEventListener('click', loadInsights);
  $('insights-filter').addEventListener('change', loadInsights);
}
async function loadInsights() {
  const f = $('insights-filter').value;
  let url = '/api/insights?guild=' + guild + '&limit=30' + (f === 'escalated' ? '&escalated=true' : '');
  const data = await api(url);
  const host = $('insights');
  host.innerHTML = '';
  data.insights.filter((i) => !f || f === 'escalated' || labelOf(i.confidence) === f).forEach((i) => {
    const label = labelOf(i.confidence);
    const div = document.createElement('div');
    div.className = 'item' + (i.escalated ? ' esc' : label === 'medium' ? ' med' : '');
    div.innerHTML = '<div>' + (i.escalated ? '<span class="badge esc">Escalated</span>' : '')
      + '<span class="badge ' + label + '">confidence: ' + label + ' - ' + Math.round(i.confidence * 100) + '%</span></div>'
      + '<div class="meta">User ' + esc(i.user) + ', channel #(' + esc(i.channel) + '), ' + fmtTime(i.createdAt) + '</div>'
      + '<div><b>Q:</b> ' + esc(i.question) + '</div>'
      + '<pre class="reason">' + esc(i.reason || '') + '</pre>'
      + '<div class="sources">Sources: ' + esc((i.sources || []).join(', ') || '—') + '</div>'
      + '<div class="actions"></div>';
    const actions = div.querySelector('.actions');
    const test = document.createElement('button'); test.className = 'ghost'; test.textContent = 'Test Chat';
    test.addEventListener('click', () => {
      document.querySelector('#nav button[data-page="test"]').click();
      $('chat-input').value = i.question;
    });
    actions.appendChild(test);
    host.appendChild(div);
  });
  if (!data.insights.length) host.innerHTML = '<div class="item"><b>No insights yet.</b></div>';
}
const labelOf = (c) => (c >= 0.8 ? 'high' : c >= 0.5 ? 'medium' : 'low');

/* ---------------- escalations ---------------- */
function wireEscalations() {
  document.querySelectorAll('input[name="maxreplies"]').forEach((r) => r.addEventListener('change', () => {
    $('maxreplies-n').disabled = document.querySelector('input[name="maxreplies"]:checked').value !== 'on';
  }));
  $('esc-save').addEventListener('click', async () => {
    const values = {
      escalationRoleId: $('esc-role').value.trim(),
      escalationPreset: document.querySelector('input[name="preset"]:checked').value,
      unknownPolicy: document.querySelector('input[name="unknown"]:checked').value,
      afterEscalation: document.querySelector('input[name="after"]:checked').value,
    };
    const th = $('esc-threshold').value.trim();
    if (th !== '') values.escalationThreshold = parseFloat(th);
    const maxOn = document.querySelector('input[name="maxreplies"]:checked').value === 'on';
    values.aiSettings = { 'max-replies-per-ticket': maxOn ? parseInt($('maxreplies-n').value || '10', 10) : 0 };
    await api('/api/settings', 'PUT', { guild, values });
    alert('Saved.');
  });
}
async function loadEscSettings() {
  const s = (await api('/api/settings?guild=' + guild)).settings || {};
  if (s.escalationRoleId) $('esc-role').value = s.escalationRoleId;
  if (s.escalationPreset) {
    const r = document.querySelector('input[name="preset"][value="' + s.escalationPreset + '"]');
    if (r) r.checked = true;
  }
  if (typeof s.escalationThreshold === 'number') $('esc-threshold').value = s.escalationThreshold;
  if (s.unknownPolicy) {
    const r = document.querySelector('input[name="unknown"][value="' + s.unknownPolicy + '"]');
    if (r) r.checked = true;
  }
  if (s.afterEscalation) {
    const r = document.querySelector('input[name="after"][value="' + s.afterEscalation + '"]');
    if (r) r.checked = true;
  }
}
async function loadEscalations() {
  const data = await api('/api/escalations?guild=' + guild + '&limit=50');
  const host = $('escs');
  if (!data.escalations.length) { host.innerHTML = '<div class="item"><b>No escalations yet.</b></div>'; return; }
  let html = '<table class="esc-table"><tr><th>Time</th><th>User</th><th>Channel</th><th>Category</th><th>Confidence</th><th>Status</th><th></th></tr>';
  data.escalations.forEach((e) => {
    html += '<tr><td>' + fmtTime(e.createdAt) + '</td><td>' + esc(e.user) + '</td><td>' + esc(e.channel) + '</td><td>' + esc(e.category) + '</td><td>' + Math.round(e.confidence * 100) + '%</td><td>' + esc(e.status) + '</td><td>' + (e.status === 'open' ? '<button class="ghost" data-arc="' + e.id + '">Archive</button>' : '') + '</td></tr>';
  });
  host.innerHTML = html + '</table>';
  host.querySelectorAll('[data-arc]').forEach((b) => b.addEventListener('click', async () => {
    await api('/api/escalations/archive', 'POST', { guild, id: parseInt(b.dataset.arc, 10) });
    loadEscalations();
  }));
}

/* ---------------- working hours ---------------- */
const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
function wireHours() {
  const sched = $('schedule');
  sched.innerHTML = '';
  DAYS.forEach((d, i) => {
    const row = document.createElement('div');
    row.className = 'day';
    row.innerHTML = '<b>' + d + '</b><input data-day="' + i + '" data-e="0" value="09:00" size="5"><span>–</span><input data-day="' + i + '" data-e="1" value="18:00" size="5"><label class="radio" style="margin:0"><input type="checkbox" data-on="' + i + '" checked> on</label>';
    sched.appendChild(row);
  });
  document.querySelectorAll('input[name="hoursmode"]').forEach((r) => r.addEventListener('change', updateHoursVisibility));
  $('hours-save').addEventListener('click', async () => {
    const mode = document.querySelector('input[name="hoursmode"]:checked').value;
    const schedule = {};
    document.querySelectorAll('#schedule input[data-day]').forEach(() => {});
    DAYS.forEach((d, i) => {
      const on = document.querySelector('#schedule input[data-on="' + i + '"]');
      if (on && on.checked) {
        const a = document.querySelector('#schedule input[data-day="' + i + '"][data-e="0"]').value;
        const b = document.querySelector('#schedule input[data-day="' + i + '"][data-e="1"]').value;
        schedule[String(i)] = [[a, b]];
      }
    });
    await api('/api/settings', 'PUT', { guild, values: {
      workingHoursMode: mode,
      workingHours: { mode, timezone: $('hours-tz').value.trim() || 'UTC', schedule,
        outOfHoursRoleId: $('hours-role').value.trim(), offlineMessage: $('hours-msg').value },
    } });
    alert('Saved.');
    loadHours();
  });
}
function updateHoursVisibility() {
  const scheduled = document.querySelector('input[name="hoursmode"]:checked').value === 'scheduled';
  $('hours-detail').classList.toggle('hidden', !scheduled);
  $('hours-chip').textContent = scheduled ? 'scheduled' : '∞ 24/7';
  $('hours-info').textContent = scheduled
    ? 'Working hours are enabled. Outside the schedule the bot stays silent.'
    : 'Working hours are disabled. The bot is available 24/7.';
}
async function loadHours() {
  const s = (await api('/api/settings?guild=' + guild)).settings || {};
  const mode = s.workingHoursMode || 'always';
  const r = document.querySelector('input[name="hoursmode"][value="' + mode + '"]');
  if (r) r.checked = true;
  const wh = s.workingHours || {};
  if (wh.timezone) $('hours-tz').value = wh.timezone;
  if (wh.outOfHoursRoleId) $('hours-role').value = wh.outOfHoursRoleId;
  if (wh.offlineMessage) $('hours-msg').value = wh.offlineMessage;
  if (wh.schedule) {
    Object.entries(wh.schedule).forEach(([day, ranges]) => {
      const r0 = ranges && ranges[0];
      if (!r0) return;
      const a = document.querySelector('#schedule input[data-day="' + day + '"][data-e="0"]');
      const b = document.querySelector('#schedule input[data-day="' + day + '"][data-e="1"]');
      if (a) a.value = r0[0]; if (b) b.value = r0[1];
    });
  }
  updateHoursVisibility();
}

/* ---------------- personality ---------------- */
let tone = 'Friendly';
function wirePersonality() {
  ['p-extra', 'p-desc', 'p-rules', 'p-never', 'p-escalate'].forEach((id) => {
    const max = id === 'p-extra' ? 1000 : 1000;
    $(id).addEventListener('input', () => {
      $({ 'p-extra': 'extra-count', 'p-desc': 'sd-count', 'p-rules': 'sr-count', 'p-never': 'nd-count', 'p-escalate': 'et-count' }[id]).textContent = $(id).value.length + '/' + max;
    });
  });
  document.querySelectorAll('#tones button').forEach((b) => b.addEventListener('click', () => {
    document.querySelectorAll('#tones button').forEach((x) => x.classList.remove('active'));
    b.classList.add('active'); tone = b.dataset.tone;
  }));
  $('p-save').addEventListener('click', async () => {
    const langForced = document.querySelector('input[name="lang"]:checked').value === 'force';
    await api('/api/settings', 'PUT', { guild, values: { personality: {
      tone, extra: $('p-extra').value, language: langForced ? $('p-langsel').value : 'Auto-detect',
      serverDescription: $('p-desc').value, serverRules: $('p-rules').value,
      neverDo: $('p-never').value, escalateTopics: $('p-escalate').value,
      detail: document.querySelector('input[name="detail"]:checked').value,
      nickname: $('p-nick').value, bio: $('p-bio').value,
    } } });
    alert('Saved.');
  });
}
async function loadPersonality() {
  const s = (await api('/api/settings?guild=' + guild)).settings || {};
  const p = s.personality || {};
  if (p.tone) {
    tone = p.tone;
    document.querySelectorAll('#tones button').forEach((x) => x.classList.toggle('active', x.dataset.tone === tone));
  }
  if (p.extra) $('p-extra').value = p.extra;
  if (p.serverDescription) $('p-desc').value = p.serverDescription;
  if (p.serverRules) $('p-rules').value = p.serverRules;
  if (p.neverDo) $('p-never').value = p.neverDo;
  if (p.escalateTopics) $('p-escalate').value = p.escalateTopics;
  if (p.nickname) $('p-nick').value = p.nickname;
  if (p.bio) $('p-bio').value = p.bio;
  if (p.detail) {
    const r = document.querySelector('input[name="detail"][value="' + p.detail + '"]');
    if (r) r.checked = true;
  }
}

/* ---------------- prompt ---------------- */
function wirePrompt() {
  $('prompt-save').addEventListener('click', async () => {
    await api('/api/prompt', 'PUT', { guild, text: $('prompt-text').value, placeholders: {
      server_ip: $('ph-server-ip').value, server_name: $('ph-server-name').value,
      server_description: $('ph-server-desc').value, staff_role: $('ph-staff-role').value,
    } });
    alert('Saved to prompt.md (hot-reloaded).');
  });
}
async function loadPrompt() {
  const data = await api('/api/prompt?guild=' + guild);
  $('prompt-text').value = data.text || '';
  const s = (await api('/api/settings?guild=' + guild)).settings || {};
  const ph = s.placeholders || {};
  if (ph.server_ip) $('ph-server-ip').value = ph.server_ip;
  if (ph.server_name) $('ph-server-name').value = ph.server_name;
  if (ph.server_description) $('ph-server-desc').value = ph.server_description;
  if (ph.staff_role) $('ph-staff-role').value = ph.staff_role;
}

/* ---------------- keys ---------------- */
function wireKeys() { $('keys-refresh').addEventListener('click', loadKeys); }
async function loadKeys() {
  const data = await api('/api/keys');
  const host = $('keys');
  host.innerHTML = '';
  (data.keys || []).forEach((k) => {
    const div = document.createElement('div');
    div.className = 'card';
    div.innerHTML = '<h3>Key …' + esc(k.last4) + (k.cooldownMs > 0 ? ' <span class="badge low">cooling ' + Math.round(k.cooldownMs / 1000) + 's</span>' : '') + '</h3>'
      + '<div class="meta">requests: ' + k.requests + ' (' + k.requestsInWindow + '/min) · tokens: ' + k.tokens + ' (' + k.tokensInWindow + '/min) · errors: ' + k.errors + '</div>'
      + (k.lastError ? '<pre class="reason">' + esc(k.lastError) + '</pre>' : '');
    host.appendChild(div);
  });
  if (!(data.keys || []).length) host.innerHTML = '<div class="card"><b>No keys configured.</b> Add Groq keys to config.yml or RDBOT_GROQ_KEY_1…</div>';
}

/* ---------------- test chat ---------------- */
function wireTest() {
  $('chat-send').addEventListener('click', sendChat);
  $('chat-input').addEventListener('keydown', (e) => { if (e.key === 'Enter') sendChat(); });
}
async function sendChat() {
  const q = $('chat-input').value.trim();
  if (!q) return;
  $('chat-input').value = '';
  const log = $('chat-log');
  const u = document.createElement('div'); u.className = 'msg user';
  u.innerHTML = '<div class="who">you</div>' + esc(q);
  log.appendChild(u);
  const data = await api('/api/test-chat', 'POST', { guild, question: q });
  const b = document.createElement('div'); b.className = 'msg';
  let html = '<div class="who">RD-Bot · ' + esc(data.category || '') + ' · ' + Math.round((data.confidence || 0) * 100) + '%</div>' + esc(data.answer || '');
  if (data.escalated) html += '<div class="esc-banner">Escalation Triggered<div style="font-weight:normal;font-size:12px">In Discord, only the escalation message is shown in the ticket channel.</div></div>';
  html += '<details><summary>Escalation Details</summary>'
    + 'Category: ' + esc(data.category || '') + '<br>Suggested Topic: ' + esc(data.suggestedTopic || '-') + '<br>'
    + 'Reason: ' + esc(data.reason || '') + '<br>Summary: ' + esc(data.summary || '') + '<br>'
    + 'Confidence: ' + (Math.round((data.confidence || 0) * 1000) / 10) + '%<br>'
    + 'Sources: ' + esc((data.sources || []).join(', ') || '—') + '</details>';
  b.innerHTML = html;
  log.appendChild(b);
  log.scrollTop = log.scrollHeight;
}

/* ---------------- logout ---------------- */
function wireLogout() {
  $('logout').addEventListener('click', async () => {
    await api('/api/logout', 'POST', {});
    location.reload();
  });
}

document.addEventListener('DOMContentLoaded', boot);
