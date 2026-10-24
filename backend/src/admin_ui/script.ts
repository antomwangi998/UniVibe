// The dashboard's script. Written without template literals so it can sit inside String.raw safely.
export const ADMIN_JS = String.raw`
(function () {
  'use strict';
  var API = '/api/v1';
  var S = { access: null, refresh: null, user: null, isAdmin: false, tab: 'reports', timer: null };
  var root = document.getElementById('app');

  // ---------- tiny helpers (everything is built with textContent, never innerHTML)
  function h(tag, attrs) {
    var el = document.createElement(tag);
    attrs = attrs || {};
    Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v === false || v == null) return;
      if (k === 'class') el.className = v;
      else if (k.indexOf('on') === 0) el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else el.setAttribute(k, v);
    });
    for (var i = 2; i < arguments.length; i++) add(el, arguments[i]);
    return el;
  }
  function add(el, c) {
    if (c == null || c === false) return;
    if (Array.isArray(c)) { c.forEach(function (x) { add(el, x); }); return; }
    el.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
  }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }
  function when(x) { return x ? new Date(x).toLocaleString() : ''; }
  function ago(x) {
    var s = Math.max(0, (Date.now() - new Date(x).getTime()) / 1000);
    if (s < 60) return 'just now'; if (s < 3600) return Math.floor(s / 60) + ' min ago';
    if (s < 86400) return Math.floor(s / 3600) + ' h ago'; return Math.floor(s / 86400) + ' d ago';
  }
  function dur(sec) {
    if (sec == null) return '-';
    if (sec < 120) return sec + ' s'; if (sec < 7200) return Math.round(sec / 60) + ' min';
    if (sec < 172800) return Math.round(sec / 3600) + ' h'; return Math.round(sec / 86400) + ' d';
  }

  // ---------- talking to the API (sign in, refresh once on 401)
  function post(path, body, token) {
    return fetch(path, {
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json' }, token ? { Authorization: 'Bearer ' + token } : {}),
      body: JSON.stringify(body || {})
    });
  }
  function takeSession(data) {
    S.access = data.accessToken; S.refresh = data.refreshToken;
    try { sessionStorage.setItem('uv_admin_rt', S.refresh); } catch (e) {}
  }
  function refreshSession() {
    if (!S.refresh) return Promise.resolve(false);
    return post('/auth/refresh', { refreshToken: S.refresh }).then(function (r) {
      if (!r.ok) return false;
      return r.json().then(function (d) { takeSession(d); return true; });
    }).catch(function () { return false; });
  }
  function api(method, path, body, retry) {
    var opts = { method: method, headers: { Authorization: 'Bearer ' + S.access } };
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    return fetch(API + path, opts).then(function (r) {
      if (r.status === 401 && !retry) {
        return refreshSession().then(function (ok) {
          if (!ok) { signOut(); throw new Error('Your session ended. Please sign in again.'); }
          return api(method, path, body, true);
        });
      }
      return r.json().catch(function () { return {}; }).then(function (data) {
        if (!r.ok) { var e = new Error(data.message || ('Request failed (' + r.status + ')')); e.status = r.status; throw e; }
        return data;
      });
    });
  }

  // ---------- sign in
  function signOut() {
    try { sessionStorage.removeItem('uv_admin_rt'); } catch (e) {}
    if (S.access && S.refresh) post('/auth/logout', { refreshToken: S.refresh }).catch(function () {});
    S.access = S.refresh = S.user = null; stopTimer(); renderLogin();
  }
  function renderLogin(message) {
    clear(root);
    var email = h('input', { type: 'email', placeholder: 'Email', autocomplete: 'username' });
    var pass = h('input', { type: 'password', placeholder: 'Password', autocomplete: 'current-password' });
    var err = h('div', { class: 'err' }, message || '');
    var go = h('button', { class: 'btn', type: 'button' }, 'Sign in');
    function submit() {
      go.disabled = true; err.textContent = '';
      post('/auth/login', { email: email.value.trim(), password: pass.value, deviceName: 'Admin dashboard' }).then(function (r) {
        return r.json().then(function (d) {
          if (!r.ok) throw new Error(d.message || 'Could not sign in.');
          takeSession(d); S.user = d.user || {}; return start();
        });
      }).catch(function (e) { err.textContent = e.message; go.disabled = false; });
    }
    go.addEventListener('click', submit);
    pass.addEventListener('keydown', function (e) { if (e.key === 'Enter') submit(); });
    root.appendChild(h('div', { class: 'login' }, h('div', { class: 'brand' }, 'Uni', h('b', null, 'Vibe'), ' admin'),
      h('p', { class: 'muted small' }, 'Moderators and administrators only.'), email, pass, err, go));
  }

  // ---------- after sign in: find out what this account may do
  function start() {
    return api('GET', '/moderation/access').then(function () {
      return api('GET', '/admin/overview').then(function () { S.isAdmin = true; }, function (e) {
        if (e.status === 404) { S.isAdmin = false; return; } throw e;
      });
    }).then(function () {
      S.tab = S.isAdmin ? 'overview' : 'reports';
      renderShell();
    }).catch(function (e) {
      var msg = e.status === 403 ? 'This account is not a moderator or administrator.' : e.message;
      S.access = null; renderLogin(msg);
    });
  }

  var TABS = [['overview', 'Overview', true], ['reports', 'Reports', false], ['users', 'People', true], ['system', 'System', true], ['audit', 'Audit log', true]];
  var body;
  function renderShell() {
    clear(root);
    var tabs = h('div', { class: 'tabs' });
    TABS.forEach(function (t) {
      if (t[2] && !S.isAdmin) return;
      tabs.appendChild(h('button', { class: 'tab' + (S.tab === t[0] ? ' on' : ''), type: 'button', onclick: function () { S.tab = t[0]; renderShell(); } }, t[1]));
    });
    root.appendChild(h('div', { class: 'top' }, h('div', { class: 'brand' }, 'Uni', h('b', null, 'Vibe'), ' admin'),
      h('div', { class: 'row' }, h('span', { class: 'muted small' }, (S.user && (S.user.fullName || S.user.name)) || ''), h('button', { class: 'btn ghost', type: 'button', onclick: signOut }, 'Sign out'))));
    root.appendChild(tabs);
    body = h('div', { class: 'wrap' });
    root.appendChild(body);
    stopTimer();
    ({ overview: viewOverview, reports: viewReports, users: viewUsers, system: viewSystem, audit: viewAudit })[S.tab]();
  }
  function stopTimer() { if (S.timer) { clearInterval(S.timer); S.timer = null; } }
  function fail(el, e) { clear(el); el.appendChild(h('div', { class: 'err' }, e.message)); }

  // ---------- 1. overview
  function kpi(label, n, tone) { return h('div', { class: 'card kpi ' + (tone || '') }, h('div', { class: 'n' }, n == null ? '-' : n.toLocaleString()), h('div', { class: 'l' }, label)); }
  function chart(title, rows) {
    var W = 300, H = 90, pad = 14, max = Math.max.apply(null, rows.map(function (r) { return r.n; }).concat([1]));
    var bw = (W - 2 * 4) / rows.length;
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H); svg.setAttribute('width', '100%');
    rows.forEach(function (r, i) {
      var bh = Math.round((r.n / max) * (H - pad - 4));
      var rect = document.createElementNS('http://www.w3.org/2000/svg', 'rect');
      rect.setAttribute('class', 'bar' + (i === rows.length - 1 ? ' today' : ''));
      rect.setAttribute('x', 4 + i * bw + 1); rect.setAttribute('width', Math.max(1, bw - 3));
      rect.setAttribute('y', H - pad - bh); rect.setAttribute('height', Math.max(bh, r.n ? 1 : 0)); rect.setAttribute('rx', 2);
      var t = document.createElementNS('http://www.w3.org/2000/svg', 'title'); t.textContent = r.day + ': ' + r.n; rect.appendChild(t);
      svg.appendChild(rect);
    });
    [[0, rows[0].day.slice(5)], [rows.length - 1, rows[rows.length - 1].day.slice(5)]].forEach(function (p, k) {
      var tx = document.createElementNS('http://www.w3.org/2000/svg', 'text');
      tx.setAttribute('x', k === 0 ? 4 : W - 4); tx.setAttribute('y', H - 2); tx.setAttribute('text-anchor', k === 0 ? 'start' : 'end'); tx.textContent = p[1]; svg.appendChild(tx);
    });
    var total = rows.reduce(function (a, r) { return a + r.n; }, 0);
    return h('div', { class: 'card' }, h('h3', null, title), h('div', { class: 'small muted' }, total.toLocaleString() + ' in 14 days'), svg);
  }
  function viewOverview() {
    body.appendChild(h('h2', null, 'Overview'));
    var out = h('div', null, 'Loading...'); body.appendChild(out);
    api('GET', '/admin/overview').then(function (d) {
      clear(out); var t = d.totals, r = d.reports;
      out.appendChild(h('div', { class: 'grid' },
        kpi('Students', t.users), kpi('Verified email', t.verified), kpi('Active today', t.activeToday), kpi('Active in 7 days', t.activeWeek),
        kpi('Suspended now', t.suspended, t.suspended ? 'warn' : ''), kpi('Open reports', r.open || 0, (r.open || 0) ? 'bad' : ''), kpi('Under review', r.under_review || 0),
        kpi('Posts', t.posts), kpi('Comments', t.comments), kpi('Messages', t.messages), kpi('Events', t.events), kpi('Communities', t.communities)));
      out.appendChild(h('div', { class: 'charts' }, chart('New students per day', d.days.signups), chart('Posts per day', d.days.posts),
        chart('Messages per day', d.days.messages), chart('Events created per day', d.days.events), chart('Reports per day', d.days.reports)));
      out.appendChild(h('p', { class: 'muted small' }, 'Counts only. This page never shows message text.'));
    }).catch(function (e) { fail(out, e); });
  }

  // ---------- 2. reports queue
  var REPORT_ACTIONS = [['remove_content', 'Remove post / comment / channel message'], ['restore_content', 'Restore removed content'], ['hide_event', 'Hide event'], ['restore_event', 'Restore event'], ['suspend_user', 'Suspend the person'], ['unsuspend_user', 'Lift suspension']];
  function previewText(p) {
    if (p == null) return '(content is no longer available)';
    if (typeof p === 'string') return p;
    return p.text || p.title || p.content || p.name || JSON.stringify(p).slice(0, 400);
  }
  function viewReports() {
    var status = 'open';
    body.appendChild(h('h2', null, 'Reports'));
    var bar = h('div', { class: 'row' }), list = h('div'), more = h('div');
    body.appendChild(bar); body.appendChild(list); body.appendChild(more);
    function drawBar() {
      clear(bar);
      [['open', 'Open'], ['under_review', 'Under review'], ['action_taken', 'Action taken'], ['dismissed', 'Dismissed']].forEach(function (s) {
        bar.appendChild(h('button', { class: 'tab' + (status === s[0] ? ' on' : ''), type: 'button', onclick: function () { status = s[0]; drawBar(); load(null); } }, s[1]));
      });
    }
    function load(cursor) {
      if (!cursor) clear(list); clear(more);
      api('GET', '/moderation/reports?status=' + status + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '')).then(function (d) {
        if (!d.items.length && !cursor) list.appendChild(h('p', { class: 'muted' }, status === 'open' ? 'Nothing waiting. All clear.' : 'Nothing here.'));
        d.items.forEach(function (r) { list.appendChild(reportCard(r)); });
        if (d.nextCursor) more.appendChild(h('button', { class: 'btn ghost', type: 'button', onclick: function () { load(d.nextCursor); } }, 'Load more'));
      }).catch(function (e) { fail(list, e); });
    }
    drawBar(); load(null);
  }
  function reportCard(r) {
    var note = h('textarea', { placeholder: 'Internal note (not shown to the person)' });
    var msg = h('input', { type: 'text', maxlength: 300, placeholder: 'Message to the person (optional, they see it)', style: false });
    var days = h('input', { type: 'number', min: 1, max: 30, value: 7, style: false });
    var sel = h('select');
    REPORT_ACTIONS.forEach(function (a) { sel.appendChild(h('option', { value: a[0] }, a[1])); });
    var out = h('div', { class: 'small' });
    var card = h('div', { class: 'card' });
    function done(text) { clear(card); card.appendChild(h('div', { class: 'ok' }, text)); }
    function review(st) {
      api('POST', '/moderation/reports/' + r.id + '/review', { status: st, note: note.value }).then(function () { done(st === 'dismissed' ? 'Dismissed.' : 'Marked under review.'); }).catch(function (e) { out.textContent = e.message; out.className = 'small err'; });
    }
    function act() {
      var b = { action: sel.value, note: note.value, userMessage: msg.value };
      if (sel.value === 'suspend_user') b.days = Math.max(1, Math.min(30, parseInt(days.value, 10) || 7));
      api('POST', '/moderation/reports/' + r.id + '/action', b).then(function () { done('Done: ' + sel.options[sel.selectedIndex].text + '.'); }).catch(function (e) { out.textContent = e.message; out.className = 'small err'; });
    }
    card.appendChild(h('div', null, h('span', { class: 'pill red' }, r.reason.replace('_', ' ')), h('span', { class: 'pill' }, r.targetType),
      r.reportCount > 1 ? h('span', { class: 'pill amber' }, r.reportCount + ' reports on this') : null, h('span', { class: 'muted small' }, ago(r.createdAt))));
    card.appendChild(h('div', { class: 'quote' }, previewText(r.preview)));
    if (r.details) card.appendChild(h('div', { class: 'small' }, h('span', { class: 'muted' }, 'Reporter says: '), r.details));
    if (r.reporter) card.appendChild(h('div', { class: 'small muted' }, 'Reported by ' + (r.reporter.name || r.reporter.fullName || 'a student')));
    if (r.status === 'open' || r.status === 'under_review') {
      card.appendChild(note); card.appendChild(msg);
      card.appendChild(h('div', { class: 'row' }, sel, h('span', { class: 'muted small' }, 'days (suspend):'), days, h('button', { class: 'btn danger', type: 'button', onclick: act }, 'Apply')));
      card.appendChild(h('div', { class: 'row' }, r.status === 'open' ? h('button', { class: 'btn ghost', type: 'button', onclick: function () { review('under_review'); } }, 'Mark under review') : null,
        h('button', { class: 'btn ghost', type: 'button', onclick: function () { review('dismissed'); } }, 'Dismiss')));
    } else card.appendChild(h('div', { class: 'small muted' }, 'Status: ' + r.status.replace('_', ' ')));
    card.appendChild(out);
    return card;
  }

  // ---------- 3. people
  function viewUsers() {
    body.appendChild(h('h2', null, 'People'));
    var q = h('input', { type: 'search', placeholder: 'Name, @handle or email (2+ letters)' });
    var go = h('button', { class: 'btn', type: 'button' }, 'Search');
    var results = h('div'), detail = h('div');
    body.appendChild(h('div', { class: 'row' }, q, go)); body.appendChild(results); body.appendChild(detail);
    function search() {
      if (q.value.trim().length < 2) return;
      clear(results); clear(detail);
      api('GET', '/admin/users?q=' + encodeURIComponent(q.value.trim())).then(function (d) {
        if (!d.items.length) results.appendChild(h('p', { class: 'muted' }, 'No one found.'));
        d.items.forEach(function (u) {
          results.appendChild(h('div', { class: 'card' }, h('button', { class: 'link', type: 'button', onclick: function () { open(u.id); } }, u.name + ' @' + u.handle),
            h('div', { class: 'small muted' }, u.email + ' - joined ' + ago(u.joinedAt)), badges(u)));
        });
      }).catch(function (e) { fail(results, e); });
    }
    function badges(u) {
      return h('div', null, u.role !== 'user' ? h('span', { class: 'pill teal' }, u.role) : null, !u.verified ? h('span', { class: 'pill amber' }, 'email not verified') : null,
        u.suspendedUntil ? h('span', { class: 'pill red' }, 'suspended until ' + when(u.suspendedUntil)) : null, u.deactivated ? h('span', { class: 'pill' }, 'deactivated') : null);
    }
    function open(id) {
      clear(detail);
      api('GET', '/admin/users/' + id).then(function (u) {
        var c = u.counts, note = h('textarea', { placeholder: 'Internal note' }), msg = h('input', { type: 'text', maxlength: 300, placeholder: 'Message the person will see (optional)' });
        var days = h('input', { type: 'number', min: 1, max: 30, value: 7 }), out = h('div', { class: 'small' });
        function run(kind) {
          var b = { note: note.value, userMessage: msg.value }; if (kind === 'suspend') b.days = Math.max(1, Math.min(30, parseInt(days.value, 10) || 7));
          api('POST', '/admin/users/' + id + '/' + kind, b).then(function () { open(id); }).catch(function (e) { out.textContent = e.message; out.className = 'small err'; });
        }
        detail.appendChild(h('div', { class: 'card' }, h('h2', null, u.name + ' @' + u.handle), h('div', { class: 'small muted' }, u.email + ' - ' + u.university), badges(u),
          h('div', { class: 'grid', style: false }, kpi('Posts', c.posts), kpi('Comments', c.comments), kpi('Messages sent', c.messages),
            kpi('Reports about them', c.reportsAboutThem, c.reportsAboutThem ? 'warn' : ''), kpi('Reports they made', c.reportsTheyMade), kpi('Signed-in devices', c.sessions)),
          u.role === 'admin' ? h('p', { class: 'muted small' }, 'Administrators cannot be suspended here.') : [note, msg,
            h('div', { class: 'row' }, u.suspendedUntil ? h('button', { class: 'btn', type: 'button', onclick: function () { run('unsuspend'); } }, 'Lift suspension')
              : [h('span', { class: 'muted small' }, 'Suspend for'), days, h('span', { class: 'muted small' }, 'days'), h('button', { class: 'btn danger', type: 'button', onclick: function () { run('suspend'); } }, 'Suspend')])],
          out,
          h('h3', null, 'Past actions'),
          u.actions.length ? h('table', null, u.actions.map(function (a) { return h('tr', null, h('td', null, when(a.at)), h('td', null, a.action.replace('_', ' ')), h('td', null, a.by || ''), h('td', null, a.note || '')); })) : h('p', { class: 'muted small' }, 'None.')));
      }).catch(function (e) { fail(detail, e); });
    }
    go.addEventListener('click', search); q.addEventListener('keydown', function (e) { if (e.key === 'Enter') search(); });
  }

  // ---------- 4. system health
  function viewSystem() {
    body.appendChild(h('h2', null, 'System'));
    var out = h('div', null, 'Loading...'); body.appendChild(out);
    function load() {
      api('GET', '/admin/health').then(function (d) {
        clear(out); var j = d.jobs, failed = Object.keys(j.failedByKind || {});
        var failedTotal = failed.reduce(function (a, k) { return a + j.failedByKind[k]; }, 0);
        out.appendChild(h('div', { class: 'grid' },
          kpi('Jobs waiting', (j.byStatus && j.byStatus.pending) || 0), kpi('Jobs failed', failedTotal, failedTotal ? 'bad' : ''), kpi('Jobs retrying', j.retrying, j.retrying ? 'warn' : ''),
          h('div', { class: 'card kpi ' + (j.oldestPendingSeconds > 120 ? 'warn' : '') }, h('div', { class: 'n' }, dur(j.oldestPendingSeconds)), h('div', { class: 'l' }, 'Oldest waiting job')),
          kpi('Push devices active', d.pushTokens.active), kpi('Push devices revoked', d.pushTokens.revoked), kpi('Uploads pending', d.media.pending, d.media.pending > 50 ? 'warn' : ''),
          kpi('Live locations on', d.liveShares)));
        if (failed.length) out.appendChild(h('div', { class: 'card' }, h('h3', null, 'Failed jobs by kind'), h('table', null, failed.map(function (k) { return h('tr', null, h('td', null, k), h('td', null, j.failedByKind[k])); }))));
        out.appendChild(h('div', { class: 'card' }, h('h3', null, 'Server'), h('table', null,
          h('tr', null, h('td', null, 'Database size'), h('td', null, (d.database.bytes / 1048576).toFixed(1) + ' MB')),
          h('tr', null, h('td', null, 'Server uptime'), h('td', null, dur(d.server.uptimeSeconds))),
          h('tr', null, h('td', null, 'Memory (in use / total)'), h('td', null, d.server.heapMb + ' / ' + d.server.rssMb + ' MB')),
          h('tr', null, h('td', null, 'Node'), h('td', null, d.server.node)),
          h('tr', null, h('td', null, 'Checked'), h('td', null, when(d.checkedAt))))));
        out.appendChild(h('p', { class: 'muted small' }, 'Refreshes every 30 seconds while this page is open.'));
      }).catch(function (e) { fail(out, e); });
    }
    load(); S.timer = setInterval(load, 30000);
  }

  // ---------- 5. audit log
  function viewAudit() {
    body.appendChild(h('h2', null, 'Audit log'));
    body.appendChild(h('p', { class: 'muted small' }, 'Every privileged action: who did what, to what, and when.'));
    var table = h('table'), more = h('div');
    table.appendChild(h('tr', null, h('th', null, 'When'), h('th', null, 'Who'), h('th', null, 'Action'), h('th', null, 'Target'), h('th', null, 'Details')));
    body.appendChild(h('div', { class: 'card' }, table)); body.appendChild(more);
    function load(cursor) {
      clear(more);
      api('GET', '/admin/audit' + (cursor ? '?cursor=' + encodeURIComponent(cursor) : '')).then(function (d) {
        d.items.forEach(function (a) {
          table.appendChild(h('tr', null, h('td', null, when(a.at)), h('td', null, a.actor ? a.actor.name : 'system'), h('td', null, a.action.replace(/_/g, ' ')),
            h('td', null, (a.targetType || '') + (a.targetId ? ' ' + a.targetId.slice(0, 8) : '')), h('td', { class: 'muted' }, a.details && Object.keys(a.details).length ? JSON.stringify(a.details) : '')));
        });
        if (d.nextCursor) more.appendChild(h('button', { class: 'btn ghost', type: 'button', onclick: function () { load(d.nextCursor); } }, 'Load more'));
      }).catch(function (e) { more.appendChild(h('div', { class: 'err' }, e.message)); });
    }
    load(null);
  }

  // ---------- go
  var saved = null; try { saved = sessionStorage.getItem('uv_admin_rt'); } catch (e) {}
  if (saved) { S.refresh = saved; refreshSession().then(function (ok) { if (!ok) return renderLogin(); return fetch('/me', { headers: { Authorization: 'Bearer ' + S.access } }).then(function (r) { return r.json(); }).then(function (u) { S.user = u; return start(); }).catch(function () { return start(); }); }); }
  else renderLogin();
})();
`;
