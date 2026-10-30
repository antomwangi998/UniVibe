// The Monetization section of the admin dashboard. It is spliced into the dashboard script (see script.ts), so it shares
// that script's helpers (h, api, body, kpi, chart, actionForm ...). Same rules as the rest of the dashboard: no template
// literals, no innerHTML, everything built with textContent. The API enforces every permission; hiding a link is courtesy.
export const MONETIZATION_JS = String.raw`
  // =====================================================================================================
  // Monetization
  // =====================================================================================================
  ICONS.revenue = 'M12 3v18M16 7.5c0-1.7-1.8-3-4-3s-4 1.3-4 3 1.8 2.8 4 3.5 4 1.8 4 3.5-1.8 3-4 3-4-1.3-4-3';
  ICONS.campaigns = 'M3 11v2a2 2 0 0 0 2 2h2l5 4V5L7 9H5a2 2 0 0 0-2 2zM16 8a5 5 0 0 1 0 8';
  ICONS.payments = 'M3 6h18v12H3zM3 10h18M7 15h3';
  ICONS.refunds = 'M3 12a9 9 0 1 0 3-6.7M3 4v5h5';
  ICONS.packages = 'M3 8l9-5 9 5v8l-9 5-9-5zM3 8l9 5 9-5M12 13v8';
  ICONS.advertisers = 'M4 21V9l8-5 8 5v12M9 21v-6h6v6';

  PAGES.revenue = { title: 'Revenue', group: 'Monetization', cap: 'finance.view', desc: 'What has been paid, refunded and earned. Payment volume is not the same as earned revenue, and each figure says which it is.', view: function (r) { viewRevenue(); } };
  PAGES.campaigns = { title: 'Campaigns', group: 'Monetization', cap: 'campaigns.review', desc: 'Review submitted promotions, pause or stop them, and check how they are performing.', view: viewCampaignsPage };
  PAGES.payments = { title: 'Payments', group: 'Monetization', cap: 'payments.view', desc: 'Receipts advertisers submitted. Nothing counts as paid until you check it against the M-Pesa statement and confirm it here.', view: viewPayments };
  PAGES.refunds = { title: 'Refunds', group: 'Monetization', cap: 'payments.view', desc: 'Money owed back to advertisers. Approve, decline, then record the M-Pesa reference once it is sent.', view: viewRefunds };
  PAGES.packages = { title: 'Packages and payment', group: 'Monetization', cap: 'packages.manage', desc: 'What advertisers can buy, what it costs, and where they pay. A price change never alters an order already placed.', view: viewPackages };
  PAGES.advertisers = { title: 'Businesses and ad reports', group: 'Monetization', cap: 'advertisers.view', desc: 'Approve business listings and look into ads students have reported.', view: viewAdvertisers };
  PAGES.premium = { title: 'Premium', group: 'Monetization', cap: 'packages.manage', desc: 'UniVibe Premium: switch plans on and off, change prices, turn a feature off for everyone if it misbehaves, and see who is subscribed.', view: viewPremium };
  NAV.splice(3, 0, ['Monetization', ['revenue', 'campaigns', 'payments', 'refunds', 'packages', 'premium', 'advertisers']]);

  // ---------- small helpers
  function kes(minor) {
    var n = Number(minor || 0), neg = n < 0; n = Math.abs(n);
    var whole = Math.floor(n / 100).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return (neg ? '-' : '') + 'KES ' + whole + '.' + ('0' + (n % 100)).slice(-2);
  }
  // "1500" or "1500.50" -> 150050, or null if it is not a plain amount.
  function toMinor(text) {
    var t = String(text || '').trim();
    if (!/^[0-9]{1,9}(\.[0-9]{1,2})?$/.test(t)) return null;
    var p = t.split('.'); return Number(p[0]) * 100 + Number((p[1] || '').padEnd(2, '0') || 0);
  }
  function words(s) { return String(s || '').replace(/_/g, ' '); }
  function tone(status) {
    if (['active', 'confirmed', 'approved', 'paid', 'paid_out', 'verified'].indexOf(status) >= 0) return ' teal';
    if (['rejected', 'failed', 'cancelled', 'suspended', 'refund_pending', 'expired', 'mismatch'].indexOf(status) >= 0) return ' red';
    if (['pending_review', 'pending_payment', 'awaiting_verification', 'requested', 'paused', 'pending'].indexOf(status) >= 0) return ' amber';
    return '';
  }
  function pill(text) { return h('span', { class: 'pill' + tone(text) }, words(text)); }
  function segTabs(page, f, key, options) {
    return h('div', { class: 'row seg' }, options.map(function (o) {
      var on = (f[key] || options[0][0]) === o[0], p = Object.assign({}, f); p[key] = o[0] === options[0][0] ? '' : o[0];
      return h('a', { class: 'tab' + (on ? ' on' : ''), href: href(page, null, p), 'aria-current': on ? 'page' : false }, o[1]);
    }));
  }
  // A list that loads a page at a time from a cursor API.
  function cursorList(container, path, params, draw, emptyText) {
    var list = h('div'), more = h('div'), count = 0;
    container.appendChild(list); container.appendChild(more);
    function query(cursor) {
      var o = Object.assign({}, params); if (cursor) o.cursor = cursor;
      var parts = Object.keys(o).filter(function (k) { return o[k] != null && o[k] !== ''; }).map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(o[k]); });
      return parts.length ? '?' + parts.join('&') : '';
    }
    function load(cursor) {
      clear(more); if (!cursor) { clear(list); count = 0; }
      more.appendChild(h('p', { class: 'muted', role: 'status' }, 'Loading...'));
      api('GET', path + query(cursor)).then(function (d) {
        clear(more);
        d.items.forEach(function (x) { count++; list.appendChild(draw(x, function () { load(null); })); });
        if (!count) list.appendChild(h('p', { class: 'muted' }, emptyText || 'Nothing here.'));
        if (d.nextCursor) more.appendChild(h('button', { class: 'btn ghost', type: 'button', onclick: function () { load(d.nextCursor); } }, 'Load more'));
      }).catch(function (e) { clear(more); fail(more, e); more.appendChild(h('button', { class: 'btn ghost', type: 'button', onclick: function () { load(cursor); } }, 'Retry')); });
    }
    load(null);
  }
  // A button that asks for nothing but a yes. For actions that need no reason.
  function confirmButton(label, confirmText, run, danger) {
    var out = h('span', { class: 'small', role: 'status' });
    var b = h('button', { class: 'btn' + (danger ? ' danger' : ''), type: 'button' }, label);
    b.addEventListener('click', function () {
      if (!window.confirm(confirmText)) return;
      b.disabled = true; out.textContent = '';
      run().catch(function (e) { b.disabled = false; out.textContent = e.message; out.className = 'small err'; });
    });
    return h('span', null, b, out);
  }
  function field(label, input) { return h('label', { class: 'row' }, h('span', { class: 'small muted' }, label), input); }

  // ---------- revenue
  function viewRevenue() {
    var out = h('div', null, 'Loading...'); body.appendChild(out);
    api('GET', '/admin/monetization/overview').then(function (d) {
      clear(out);
      function money(label, minor, note, toneClass) { return h('div', { class: 'card kpi ' + (toneClass || '') }, h('div', { class: 'n' }, kes(minor)), h('div', { class: 'l' }, label), note ? h('div', { class: 'small muted' }, note) : null); }
      out.appendChild(h('h2', null, 'Payments'));
      out.appendChild(h('div', { class: 'grid' },
        money('Received', d.payments.receivedMinor, 'Payment volume, before refunds'), money('Refunded', d.payments.refundedMinor, 'Paid back to advertisers'),
        money('Net collected', d.payments.netCollectedMinor, 'Received minus refunded'),
        kpi('Waiting for your check', d.payments.awaitingConfirmation, d.payments.awaitingConfirmation ? 'warn' : '', href('payments')),
        kpi('Failed in 30 days', d.payments.failedLast30Days, '', href('payments', null, { status: 'failed' })),
        kpi('Refunds open', d.refunds.open, d.refunds.open ? 'warn' : '', href('refunds'))));
      out.appendChild(h('h2', null, 'Earned revenue'));
      out.appendChild(h('div', { class: 'grid' }, money('Recognised', d.revenue.recognisedMinor, 'Treated as earned so far', 'ok'), money('Deferred', d.revenue.deferredMinor, 'Paid for, still to be delivered'), money('Paid orders', d.revenue.paidOrdersMinor, 'Recognised plus deferred')));
      var t = h('table'); t.appendChild(h('tr', null, h('th', null, 'Campaign type'), h('th', null, 'Paid orders'), h('th', null, 'Recognised')));
      d.revenue.byKind.forEach(function (k) { t.appendChild(h('tr', null, h('td', null, words(k.kind)), h('td', null, kes(k.paidMinor)), h('td', null, kes(k.recognisedMinor)))); });
      if (!d.revenue.byKind.length) t.appendChild(h('tr', null, h('td', { colspan: 3, class: 'muted' }, 'No paid campaigns yet.')));
      out.appendChild(h('div', { class: 'card scroll' }, t));
      out.appendChild(h('h2', null, 'Campaigns'));
      out.appendChild(h('div', { class: 'grid' }, kpi('Active, paid', d.campaigns.activePaid), kpi('Waiting for review', d.campaigns.pendingReview, d.campaigns.pendingReview ? 'warn' : '', href('campaigns', null, { status: 'pending_review' })), kpi('With open ad reports', d.campaigns.withOpenAdReports, d.campaigns.withOpenAdReports ? 'bad' : '', href('advertisers'))));
      out.appendChild(h('h2', null, 'By month'));
      if (d.byMonth.length) {
        var mt = h('table'); mt.appendChild(h('tr', null, h('th', null, 'Month'), h('th', null, 'Received'), h('th', null, 'Refunded'), h('th', null, 'Net')));
        d.byMonth.forEach(function (m) { mt.appendChild(h('tr', null, h('td', null, m.month), h('td', null, kes(m.receivedMinor)), h('td', null, kes(m.refundedMinor)), h('td', null, kes(m.receivedMinor - m.refundedMinor)))); });
        out.appendChild(h('div', { class: 'card scroll' }, mt));
      } else out.appendChild(h('p', { class: 'muted' }, 'No confirmed payments yet.'));
      out.appendChild(h('div', { class: 'card' }, h('b', null, 'Costs and operating surplus'), h('p', { class: 'small muted' }, d.rules.costs)));
      var rules = h('ul'); Object.keys(d.rules).forEach(function (k) { if (k !== 'costs') rules.appendChild(h('li', { class: 'small' }, h('b', null, words(k) + ': '), d.rules[k])); });
      out.appendChild(h('details', { class: 'card' }, h('summary', null, 'How these figures are worked out'), rules));
    }).catch(function (e) { fail(out, e); });
  }

  // ---------- campaigns
  var CAMPAIGN_TABS = [['pending_review', 'Waiting for review'], ['active', 'Active'], ['scheduled', 'Scheduled'], ['paused', 'Paused'], ['pending_payment', 'Awaiting payment'], ['refund_pending', 'Refund pending'], ['completed', 'Completed'], ['rejected', 'Rejected'], ['cancelled', 'Cancelled'], ['all', 'All']];
  function viewCampaignsPage(r) {
    if (r.id) return viewCampaign(r.id);
    var f = { status: r.params.status === undefined ? 'pending_review' : r.params.status };
    var tabs = h('div', { class: 'row seg' }, CAMPAIGN_TABS.map(function (o) {
      var on = f.status === o[0];
      return h('a', { class: 'tab' + (on ? ' on' : ''), href: href('campaigns', null, { status: o[0] === 'pending_review' ? '' : o[0] }), 'aria-current': on ? 'page' : false }, o[1]);
    }));
    body.appendChild(tabs);
    var box = h('div'); body.appendChild(box);
    cursorList(box, '/admin/monetization/campaigns', { status: f.status === 'all' ? '' : f.status }, campaignCard, 'No campaigns in this view.');
  }
  function campaignCard(c, reload) {
    var base = '/admin/monetization/campaigns/' + c.id;
    var go = function (action, extra) { return function (b) { return api('POST', base + '/' + action, Object.assign({ note: b.note }, extra || {})).then(reload); }; };
    var acts = [];
    if (has('campaigns.review')) {
      if (c.status === 'pending_review') {
        acts.push(confirmButton('Approve', 'Approve this promotion? It will start as soon as its dates and payment allow.', function () { return api('POST', base + '/approve', {}).then(reload); }));
        acts.push(actionForm('Reject', true, 'Reject this promotion? A paid order will move to refund review.', go('reject')));
      }
      if (c.status === 'active' || c.status === 'scheduled') acts.push(actionForm('Pause', false, 'Pause this promotion?', go('pause')));
      if (c.status === 'paused') acts.push(actionForm('Resume', false, 'Resume this promotion?', go('resume')));
      if (['approved', 'scheduled', 'active', 'paused', 'pending_review'].indexOf(c.status) >= 0) acts.push(actionForm('Cancel', true, 'Cancel this promotion? Undelivered paid orders are queued for refund.', go('cancel')));
    }
    var ctr = c.delivered_impressions > 0 ? (100 * c.delivered_clicks / c.delivered_impressions).toFixed(1) + '%' : '-';
    return h('div', { class: 'card' },
      h('div', null, pill(c.status), c.order_status ? h('span', { class: 'pill' + tone(c.order_status) }, 'payment: ' + words(c.order_status)) : h('span', { class: 'pill' }, 'no order'), h('span', { class: 'pill' }, words(c.kind))),
      h('div', null, h('a', { class: 'link', href: href('campaigns', c.id) }, h('b', null, c.headline))),
      h('div', { class: 'small muted' }, (c.advertiser_handle ? '@' + c.advertiser_handle : 'removed account') + ' - ' + when(c.starts_at) + ' to ' + when(c.ends_at) + (c.amount ? ' - ' + c.amount : '')),
      h('div', { class: 'small muted' }, Number(c.delivered_impressions).toLocaleString() + ' impressions, ' + Number(c.delivered_clicks).toLocaleString() + ' clicks, CTR ' + ctr),
      acts.length ? h('div', { class: 'row' }, acts) : null);
  }
  function viewCampaign(id) {
    var out = h('div', null, 'Loading...'); body.appendChild(out);
    setHeader('Campaign', 'Performance, delivery status and history.', [['Campaigns', href('campaigns')], ['Details']]);
    Promise.all([api('GET', '/admin/monetization/campaigns/' + id), api('GET', '/admin/monetization/campaigns/' + id + '/analytics')]).then(function (res) {
      var c = res[0].campaign, history = res[0].history, a = res[1];
      clear(out);
      setHeader(c.headline, 'Performance, delivery status and history.', [['Campaigns', href('campaigns')], [c.headline]]);
      out.appendChild(h('div', { class: 'card' }, h('div', null, pill(a.campaign.status), pill(c.review_status), a.payment ? h('span', { class: 'pill' + tone(a.payment.status) }, 'payment: ' + words(a.payment.status)) : null),
        h('div', { class: 'small muted' }, when(c.starts_at) + ' to ' + when(c.ends_at)), c.review_note ? h('div', { class: 'quote' }, c.review_note) : null,
        h('div', { class: 'small' }, a.delivery.running ? 'Delivering now.' : 'Not delivering: ' + (a.delivery.reasonsNotRunning.join(' ') || (a.delivery.heldForReview ? 'Held for review after student reports.' : 'unknown reason.')))));
      out.appendChild(h('div', { class: 'grid' }, kpi('Impressions', a.totals.impressions), kpi('Clicks', a.totals.clicks),
        h('div', { class: 'card kpi' }, h('div', { class: 'n' }, a.totals.ctr == null ? '-' : (a.totals.ctr * 100).toFixed(2) + '%'), h('div', { class: 'l' }, 'Click-through rate')),
        kpi('Reach (students)', a.totals.reach), a.conversions ? kpi('RSVPs after a click', a.conversions.count) : null));
      if (a.daily.length) out.appendChild(chart('Impressions per day', a.daily.map(function (x) { return { day: x.day, n: x.impressions }; }), a.range.from + ' to ' + a.range.to));
      var flags = a.integrity ? a.integrity.flags : [];
      out.appendChild(h('div', { class: 'card' }, h('b', null, 'Engagement checks'),
        flags.length ? h('ul', null, flags.map(function (x) { return h('li', { class: 'small' }, x.message); })) : h('p', { class: 'small muted' }, 'Nothing unusual found in the numbers so far.'),
        h('p', { class: 'small muted' }, a.integrity ? a.integrity.note : '')));
      var defs = h('ul'); Object.keys(a.definitions).forEach(function (k) { defs.appendChild(h('li', { class: 'small' }, h('b', null, words(k) + ': '), a.definitions[k])); });
      out.appendChild(h('details', { class: 'card' }, h('summary', null, 'What these measures mean'), defs));
      var t = h('table'); t.appendChild(h('tr', null, h('th', null, 'When'), h('th', null, 'Status'), h('th', null, 'By'), h('th', null, 'Note')));
      history.forEach(function (x) { t.appendChild(h('tr', null, h('td', null, when(x.created_at)), h('td', null, words(x.to_status)), h('td', null, x.handle ? '@' + x.handle : x.actor_kind), h('td', { class: 'muted' }, x.note || ''))); });
      out.appendChild(h('h2', null, 'History')); out.appendChild(h('div', { class: 'card scroll' }, t));
    }).catch(function (e) { fail(out, e); });
  }

  // ---------- payments
  function viewPayments(r) {
    var f = { status: r.params.status === undefined ? 'awaiting_verification' : r.params.status };
    var opts = [['awaiting_verification', 'Waiting for your check'], ['confirmed', 'Confirmed'], ['failed', 'Rejected or failed'], ['refunded', 'Refunded'], ['all', 'All']];
    body.appendChild(h('div', { class: 'row seg' }, opts.map(function (o) {
      var on = f.status === o[0];
      return h('a', { class: 'tab' + (on ? ' on' : ''), href: href('payments', null, { status: o[0] === 'awaiting_verification' ? '' : o[0] }), 'aria-current': on ? 'page' : false }, o[1]);
    })));
    var box = h('div'); body.appendChild(box);
    cursorList(box, '/admin/monetization/payments', { status: f.status === 'all' ? '' : f.status }, paymentCard, 'No payments in this view.');
  }
  function paymentCard(p, reload) {
    var acts = null;
    if (p.status === 'awaiting_verification' && has('payments.confirm')) {
      var amount = h('input', { type: 'text', inputmode: 'decimal', value: (p.amountMinor / 100).toFixed(2), 'aria-label': 'Amount received in KES', size: 10 });
      var excess = h('input', { type: 'checkbox', id: 'ex-' + p.id });
      var out = h('div', { class: 'small', role: 'status' });
      var ok = h('button', { class: 'btn', type: 'button' }, 'Confirm payment');
      ok.addEventListener('click', function () {
        var minor = toMinor(amount.value);
        if (minor == null) { out.textContent = 'Enter the amount as a plain number, for example 500 or 500.00.'; out.className = 'small err'; return; }
        if (!window.confirm('Confirm that ' + kes(minor) + ' with receipt ' + p.receipt + ' appears on the M-Pesa statement?')) return;
        ok.disabled = true; out.textContent = '';
        api('POST', '/admin/monetization/payments/' + p.id + '/confirm', { receivedMinor: minor, refundExcess: excess.checked }).then(reload)
          .catch(function (e) { ok.disabled = false; out.textContent = e.message; out.className = 'small err'; });
      });
      acts = h('div', null, h('div', { class: 'row' }, field('Amount received (KES)', amount), h('label', { class: 'row small' }, excess, 'Refund any excess'), ok), out,
        actionForm('Reject', true, 'Reject this payment? The advertiser is told and can submit another receipt.', function (b) { return api('POST', '/admin/monetization/payments/' + p.id + '/reject', { reason: b.note }).then(reload); }));
    }
    return h('div', { class: 'card' },
      h('div', null, pill(p.status), p.reconciliation === 'mismatch' ? h('span', { class: 'pill red' }, 'amount did not match') : null, p.refundStatus ? h('span', { class: 'pill amber' }, 'refund: ' + words(p.refundStatus)) : null),
      h('div', null, h('b', null, kes(p.amountMinor)), ' - order ', h('b', null, p.orderReference), ' - receipt ', h('b', null, p.receipt || '-')),
      h('div', { class: 'small muted' }, (p.advertiser ? '@' + p.advertiser : 'removed account') + ' - ' + (p.campaign || 'no campaign') + ' - submitted ' + when(p.submittedAt) + (p.confirmedAt ? ' - confirmed ' + when(p.confirmedAt) : '')),
      p.receivedMinor != null && p.receivedMinor !== p.amountMinor ? h('div', { class: 'small' }, 'Received ' + kes(p.receivedMinor) + ' against ' + kes(p.amountMinor) + ' due.') : null,
      p.failureReason ? h('div', { class: 'quote' }, p.failureReason) : null, acts);
  }

  // ---------- refunds
  function viewRefunds(r) {
    var f = { status: r.params.status === undefined ? 'requested' : r.params.status };
    var opts = [['requested', 'Waiting for a decision'], ['approved', 'Approved, not yet sent'], ['paid_out', 'Sent'], ['rejected', 'Declined'], ['all', 'All']];
    body.appendChild(h('div', { class: 'row seg' }, opts.map(function (o) {
      var on = f.status === o[0];
      return h('a', { class: 'tab' + (on ? ' on' : ''), href: href('refunds', null, { status: o[0] === 'requested' ? '' : o[0] }), 'aria-current': on ? 'page' : false }, o[1]);
    })));
    var box = h('div'); body.appendChild(box);
    cursorList(box, '/admin/monetization/refunds', { status: f.status === 'all' ? '' : f.status }, refundCard, 'No refunds in this view.');
  }
  function refundCard(x, reload) {
    var base = '/admin/monetization/refunds/' + x.id, acts = null;
    if (has('refunds.approve')) {
      if (x.status === 'requested') acts = h('div', { class: 'row' }, confirmButton('Approve', 'Approve this refund of ' + x.amount + '? You will still need to send the money and record the reference.', function () { return api('POST', base + '/approve', {}).then(reload); }),
        actionForm('Decline', true, 'Decline this refund?', function (b) { return api('POST', base + '/reject', { note: b.note }).then(reload); }));
      else if (x.status === 'approved') {
        var ref = h('input', { type: 'text', maxlength: 60, placeholder: 'M-Pesa reference of the refund', 'aria-label': 'M-Pesa reference of the refund' });
        var out = h('div', { class: 'small', role: 'status' }), go = h('button', { class: 'btn', type: 'button' }, 'Record payout');
        go.addEventListener('click', function () {
          if (ref.value.trim().length < 6) { out.textContent = 'Enter the reference from the M-Pesa message for the refund you sent.'; out.className = 'small err'; return; }
          if (!window.confirm('Record that ' + x.amount + ' was sent back with reference ' + ref.value.trim() + '?')) return;
          go.disabled = true; api('POST', base + '/paid', { payoutReference: ref.value.trim() }).then(reload).catch(function (e) { go.disabled = false; out.textContent = e.message; out.className = 'small err'; });
        });
        acts = h('div', null, h('div', { class: 'row' }, ref, go), out);
      }
    }
    return h('div', { class: 'card' }, h('div', null, pill(x.status), h('span', { class: 'pill' }, x.kind === 'overpayment' ? 'excess handed back' : 'full refund')),
      h('div', null, h('b', null, x.amount), ' - order ', h('b', null, x.order_reference), ' - receipt ', h('b', null, x.provider_reference || '-')),
      h('div', { class: 'small muted' }, 'Asked ' + when(x.created_at)), h('div', { class: 'quote' }, x.reason),
      x.decision_note ? h('div', { class: 'small muted' }, 'Decision note: ' + x.decision_note) : null,
      x.payout_reference ? h('div', { class: 'small' }, 'Sent, reference ' + x.payout_reference) : null, acts);
  }

  // ---------- packages and the place to pay
  var KIND_LABELS = [['event_promotion', 'Event promotion'], ['featured_business', 'Featured business'], ['sponsored_post', 'Sponsored post'], ['featured_discover', 'Featured in Discover']];
  var PLACEMENT_LABELS = [['home_feed', 'Home feed'], ['discover_feed', 'Discover feed'], ['discover_events', 'Discover events'], ['business_directory', 'Business directory']];
  function viewPackages() {
    var settings = h('div', { class: 'card' }, 'Loading...'), list = h('div'), form = h('div', { class: 'card' });
    body.appendChild(settings); body.appendChild(h('h2', null, 'Packages')); body.appendChild(list); body.appendChild(form);
    function loadSettings() {
      api('GET', '/admin/monetization/payment-settings').then(function (s) {
        clear(settings);
        var enabled = h('input', { type: 'checkbox', checked: s.enabled ? 'checked' : false, id: 'pay-on' });
        var method = pick('Method', s.method || 'till', [['till', 'Till number (Buy Goods)'], ['paybill', 'Paybill']], function () {});
        var number = h('input', { type: 'text', inputmode: 'numeric', maxlength: 10, value: s.number || '', 'aria-label': 'Till or paybill number', placeholder: 'Number' });
        var name = h('input', { type: 'text', maxlength: 60, value: s.accountName || '', 'aria-label': 'Name shown on the M-Pesa prompt', placeholder: 'Business name as M-Pesa shows it' });
        var how = h('textarea', { maxlength: 300, 'aria-label': 'Extra instructions', placeholder: 'Anything advertisers should know (optional)' }); how.value = s.instructions || '';
        var out = h('div', { class: 'small', role: 'status' }), save = h('button', { class: 'btn', type: 'button' }, 'Save');
        save.addEventListener('click', function () {
          if (enabled.checked && !window.confirm('Switch payments on? Advertisers will be shown this number and asked to pay it.')) return;
          save.disabled = true; out.textContent = '';
          api('PUT', '/admin/monetization/payment-settings', { enabled: enabled.checked, method: method.value, number: number.value.trim(), accountName: name.value.trim(), instructions: how.value.trim() })
            .then(function () { save.disabled = false; out.textContent = 'Saved.'; out.className = 'small ok'; }).catch(function (e) { save.disabled = false; out.textContent = e.message; out.className = 'small err'; });
        });
        settings.appendChild(h('h2', null, 'Where advertisers pay'));
        settings.appendChild(h('p', { class: 'small muted' }, 'Until the Daraja integration exists, advertisers pay this number by M-Pesa and send you the receipt code. Payments stay off until you switch them on with a number entered.'));
        settings.appendChild(h('label', { class: 'row' }, enabled, 'Payments switched on'));
        settings.appendChild(h('div', { class: 'row' }, method, number)); settings.appendChild(h('div', { class: 'row' }, name)); settings.appendChild(how);
        settings.appendChild(h('div', { class: 'row' }, save)); settings.appendChild(out);
      }).catch(function (e) { fail(settings, e); });
    }
    function loadList() {
      clear(list); list.appendChild(h('p', { class: 'muted' }, 'Loading...'));
      api('GET', '/admin/monetization/packages').then(function (d) {
        clear(list);
        if (!d.items.length) list.appendChild(h('p', { class: 'muted' }, 'No packages yet. Create one below; advertisers cannot buy anything until you do.'));
        d.items.forEach(function (p) { list.appendChild(packageCard(p, loadList)); });
      }).catch(function (e) { fail(list, e); });
    }
    function packageCard(p, reload) {
      var price = h('input', { type: 'text', inputmode: 'decimal', value: (p.priceMinor / 100).toFixed(2), 'aria-label': 'Price in KES', size: 10 });
      var out = h('div', { class: 'small', role: 'status' }), set = h('button', { class: 'btn ghost', type: 'button' }, 'Change price');
      set.addEventListener('click', function () {
        var minor = toMinor(price.value);
        if (minor == null) { out.textContent = 'Enter the price as a plain number, for example 500 or 500.00.'; out.className = 'small err'; return; }
        if (minor === p.priceMinor) { out.textContent = 'That is already the price.'; return; }
        if (!window.confirm('Change the price of ' + p.name + ' from ' + kes(p.priceMinor) + ' to ' + kes(minor) + '? Orders already placed keep their price.')) return;
        set.disabled = true; api('PATCH', '/admin/monetization/packages/' + p.id, { priceMinor: minor, confirmPriceChange: true }).then(reload).catch(function (e) { set.disabled = false; out.textContent = e.message; out.className = 'small err'; });
      });
      return h('div', { class: 'card' },
        h('div', null, h('span', { class: 'pill' + (p.active ? ' teal' : '') }, p.active ? 'available' : 'hidden'), h('span', { class: 'pill' }, words(p.kind))),
        h('div', null, h('b', null, p.name), ' - ', kes(p.priceMinor), ' for up to ', p.durationDays + ' days'),
        h('div', { class: 'small muted' }, p.placements.map(words).join(', ') + (p.impressionCap ? ' - up to ' + p.impressionCap.toLocaleString() + ' impressions' : ' - no impression limit') + ' - at most ' + p.frequencyCapPerDay + ' a day per student'),
        h('div', { class: 'small muted' }, 'Code: ' + p.code + (p.description ? ' - ' + p.description : '')),
        h('div', { class: 'row' }, field('Price (KES)', price), set, confirmButton(p.active ? 'Hide from advertisers' : 'Make available', (p.active ? 'Hide ' : 'Make available ') + p.name + '?', function () { return api('PATCH', '/admin/monetization/packages/' + p.id, { active: !p.active }).then(reload); })), out);
    }
    function buildForm() {
      var code = h('input', { type: 'text', maxlength: 40, placeholder: 'code, e.g. event-week', 'aria-label': 'Package code' });
      var kind = pick('Kind', 'event_promotion', KIND_LABELS, function () {});
      var name = h('input', { type: 'text', maxlength: 60, placeholder: 'Name advertisers see', 'aria-label': 'Package name' });
      var desc = h('input', { type: 'text', maxlength: 500, placeholder: 'Short description (optional)', 'aria-label': 'Description' });
      var price = h('input', { type: 'text', inputmode: 'decimal', placeholder: 'Price in KES (0 for free)', 'aria-label': 'Price in KES', size: 12 });
      var days = h('input', { type: 'number', min: 1, max: 90, value: 7, 'aria-label': 'Days it can run' });
      var cap = h('input', { type: 'number', min: 1, placeholder: 'Impression limit (optional)', 'aria-label': 'Impression limit' });
      var freq = h('input', { type: 'number', min: 1, max: 20, value: 3, 'aria-label': 'Most views per student per day' });
      var places = PLACEMENT_LABELS.map(function (p) { var c = h('input', { type: 'checkbox', value: p[0] }); if (p[0] === 'home_feed') c.checked = true; return [c, p[1]]; });
      var out = h('div', { class: 'small', role: 'status' }), go = h('button', { class: 'btn', type: 'button' }, 'Create package');
      go.addEventListener('click', function () {
        var minor = toMinor(price.value);
        if (minor == null) { out.textContent = 'Enter the price as a plain number, for example 500 or 0.'; out.className = 'small err'; return; }
        var chosen = places.filter(function (p) { return p[0].checked; }).map(function (p) { return p[0].value; });
        if (!chosen.length) { out.textContent = 'Choose at least one place it can appear.'; out.className = 'small err'; return; }
        if (!window.confirm('Create ' + name.value.trim() + ' at ' + kes(minor) + '?')) return;
        go.disabled = true;
        api('POST', '/admin/monetization/packages', { code: code.value.trim(), kind: kind.value, name: name.value.trim(), description: desc.value.trim(), priceMinor: minor, durationDays: Number(days.value),
          placements: chosen, impressionCap: cap.value ? Number(cap.value) : null, frequencyCapPerDay: Number(freq.value) })
          .then(function () { go.disabled = false; out.textContent = 'Created.'; out.className = 'small ok'; code.value = name.value = desc.value = price.value = ''; loadList(); })
          .catch(function (e) { go.disabled = false; out.textContent = e.message; out.className = 'small err'; });
      });
      form.appendChild(h('h2', null, 'New package'));
      form.appendChild(h('div', { class: 'row' }, code, kind)); form.appendChild(h('div', { class: 'row' }, name)); form.appendChild(h('div', { class: 'row' }, desc));
      form.appendChild(h('div', { class: 'row' }, price, field('Days', days), field('Views per student per day', freq), cap));
      form.appendChild(h('div', { class: 'row' }, places.map(function (p) { return h('label', { class: 'row small' }, p[0], p[1]); })));
      form.appendChild(h('div', { class: 'row' }, go)); form.appendChild(out);
    }
    loadSettings(); loadList(); buildForm();
  }

  // ---------- businesses and ad reports
  function viewAdvertisers(r) {
    var f = { status: r.params.status === undefined ? 'pending_review' : r.params.status };
    var opts = [['pending_review', 'Waiting for review'], ['approved', 'Approved'], ['rejected', 'Rejected'], ['suspended', 'Suspended'], ['all', 'All']];
    body.appendChild(h('h2', null, 'Business listings'));
    body.appendChild(h('div', { class: 'row seg' }, opts.map(function (o) {
      var on = f.status === o[0];
      return h('a', { class: 'tab' + (on ? ' on' : ''), href: href('advertisers', null, { status: o[0] === 'pending_review' ? '' : o[0] }), 'aria-current': on ? 'page' : false }, o[1]);
    })));
    var box = h('div'); body.appendChild(box);
    cursorList(box, '/admin/monetization/businesses', { status: f.status === 'all' ? '' : f.status }, businessCard, 'No businesses in this view.');
    if (has('campaigns.review')) {
      body.appendChild(h('h2', null, 'Reported ads'));
      var rep = h('div'); body.appendChild(rep); loadReports(rep);
    }
    body.appendChild(h('p', { class: 'small muted' }, 'To restrict an advertiser account, open the person on the People page and suspend them there.'));
  }
  function businessCard(b, reload) {
    var base = '/admin/monetization/businesses/' + b.id + '/review', acts = [];
    var decide = function (decision) { return function (x) { return api('POST', base, { decision: decision, note: x.note }).then(reload); }; };
    var plain = function (decision) { return function () { return api('POST', base, { decision: decision }).then(reload); }; };
    if (has('campaigns.review')) {
      if (b.status === 'pending_review') { acts.push(confirmButton('Approve', 'Approve this business listing?', plain('approve'))); acts.push(actionForm('Reject', true, 'Reject this listing? The owner sees your reason.', decide('reject'))); }
      if (b.status === 'approved') { acts.push(actionForm('Suspend', true, 'Suspend this listing? Its running promotions will stop.', decide('suspend'))); acts.push(b.verification === 'verified' ? confirmButton('Remove verification', 'Remove the verified mark?', plain('unverify')) : confirmButton('Mark verified', 'Mark this business as verified? Only do this after checking who runs it.', plain('verify'))); }
      if (b.status === 'suspended') acts.push(confirmButton('Restore', 'Restore this listing?', plain('restore')));
    }
    return h('div', { class: 'card' }, h('div', null, pill(b.status), b.verification === 'verified' ? h('span', { class: 'pill teal' }, 'verified') : null, h('span', { class: 'pill' }, b.category)),
      h('div', null, h('b', null, b.name)), h('div', { class: 'small muted' }, (b.owner_handle ? '@' + b.owner_handle : 'removed account') + ' - ' + b.campaigns + ' campaign' + (Number(b.campaigns) === 1 ? '' : 's') + ' - created ' + when(b.created_at)),
      b.review_note ? h('div', { class: 'quote' }, b.review_note) : null, acts.length ? h('div', { class: 'row' }, acts) : null);
  }
  function loadReports(el) {
    clear(el); el.appendChild(h('p', { class: 'muted' }, 'Loading...'));
    api('GET', '/admin/monetization/ad-reports').then(function (d) {
      clear(el);
      if (!d.items.length) { el.appendChild(h('p', { class: 'muted' }, 'No ads are waiting on reports.')); return; }
      d.items.forEach(function (x) {
        var base = '/admin/monetization/ad-reports/' + x.campaign_id + '/resolve', again = function () { loadReports(el); };
        el.appendChild(h('div', { class: 'card' }, h('div', null, pill(x.status), h('span', { class: 'pill red' }, x.open_reports + ' open report' + (x.open_reports === 1 ? '' : 's'))),
          h('div', null, h('a', { class: 'link', href: href('campaigns', x.campaign_id) }, h('b', null, x.headline))),
          h('div', { class: 'small muted' }, (x.advertiser ? '@' + x.advertiser : 'removed account') + ' - reasons: ' + (x.reasons || []).map(words).join(', ') + ' - oldest ' + ago(x.oldest)),
          h('div', { class: 'row' }, confirmButton('Dismiss reports', 'Dismiss these reports and let the ad continue?', function () { return api('POST', base, { decision: 'dismissed' }).then(again); }),
            actionForm('Uphold', true, 'Uphold the reports? A running ad is paused.', function (b) { return api('POST', base, { decision: 'upheld', note: b.note }).then(again); }))));
      });
    }).catch(function (e) { fail(el, e); });
  }

  // ---------- Premium: plans, feature switches, subscribers
  var FEATURE_LABELS = {
    'premium.chat_themes': 'Chat theme packs', 'premium.profile_themes': 'Profile themes, accent and badge', 'premium.profile_frames': 'Profile avatar frames',
    'premium.sticker_packs': 'Sticker packs (not built)', 'premium.creator_analytics': 'Post insights (not built)',
    'premium.post_scheduling': 'Post scheduling (not built)', 'premium.advanced_feed_controls': 'Feed controls (not built)',
    'business.analytics': 'Business analytics (not built)', 'business.offers': 'Business offers (not built)', 'business.scheduled_posts': 'Business scheduled posts (not built)'
  };
  function viewPremium() {
    var out = h('div', null, 'Loading...'); body.appendChild(out);
    function load() {
      api('GET', '/admin/monetization/premium/overview').then(function (d) { draw(d); }).catch(function (e) { fail(out, e); });
    }
    function check(ok, text) { return h('div', { class: 'row' }, h('span', { class: 'pill' + (ok ? ' teal' : ' amber') }, ok ? 'ready' : 'not yet'), h('span', null, text)); }
    function draw(d) {
      clear(out);
      var anyOn = d.plans.some(function (p) { return p.active; });
      out.appendChild(h('h2', null, 'Ready to sell?'));
      out.appendChild(h('div', { class: 'card' },
        check(d.payments.enabled, 'Payments are switched on with a till or paybill number (Packages and payment page).'),
        check(anyOn, 'At least one plan is switched on below.'),
        check(d.features.some(function (f) { return f.enabled && f.inAnyPlan; }), 'At least one feature in a plan is switched on.'),
        h('p', { class: 'small muted' }, 'Students pay by M-Pesa and send a receipt code. Their Premium starts only when you confirm the payment on the Payments page. Nothing renews by itself.')));
      out.appendChild(h('div', { class: 'grid' },
        kpi('Students with Premium now', d.totals.liveStudents, ''), kpi('Businesses subscribed now', d.totals.liveBusinesses, ''),
        kpi('Orders waiting for payment', d.totals.pendingOrders, ''), h('div', { class: 'card kpi' }, h('div', { class: 'n' }, kes(d.totals.confirmedGrossMinor)), h('div', { class: 'l' }, 'Confirmed subscription payments'), h('div', { class: 'small muted' }, 'Before refunds. Not earned revenue.'))));

      out.appendChild(h('h2', null, 'Plans'));
      d.plans.forEach(function (p) { out.appendChild(planCard(p)); });

      out.appendChild(h('h2', null, 'Features'));
      out.appendChild(h('p', { class: 'small muted' }, 'Turning a feature off removes it for everyone at once, even people who paid. Nobody loses days: when you turn it back on it works again for the rest of their period.'));
      var t = h('table'); t.appendChild(h('tr', null, h('th', null, 'Feature'), h('th', null, 'In a plan'), h('th', null, 'Status'), h('th', null, '')));
      d.features.forEach(function (f) {
        t.appendChild(h('tr', null, h('td', null, FEATURE_LABELS[f.key] || f.key, h('div', { class: 'small muted' }, f.key)), h('td', null, f.inAnyPlan ? 'yes' : 'no'), h('td', null, pill(f.enabled ? 'active' : 'suspended')),
          h('td', null, confirmButton(f.enabled ? 'Turn off for everyone' : 'Turn on', (f.enabled ? 'Turn off ' : 'Turn on ') + (FEATURE_LABELS[f.key] || f.key) + (f.enabled ? ' for everyone, including people who paid?' : '?'), function () { return api('PATCH', '/admin/monetization/premium/features/' + encodeURIComponent(f.key), { enabled: !f.enabled }).then(load); }, f.enabled))));
      });
      out.appendChild(t);

      out.appendChild(h('h2', null, 'Subscribers'));
      var state = 'live', holder = h('div'), tabs = h('div', { class: 'row seg' });
      function show() {
        clear(tabs); clear(holder);
        [['live', 'Active now'], ['ended', 'Ended'], ['refunded', 'Refunded']].forEach(function (o) {
          tabs.appendChild(h('button', { class: 'tab' + (state === o[0] ? ' on' : ''), type: 'button', onclick: function () { state = o[0]; show(); } }, o[1]));
        });
        cursorList(holder, '/admin/monetization/subscriptions', { state: state }, function (x) {
          return h('div', { class: 'card' }, h('b', null, x.name), ' ', h('span', { class: 'muted' }, '@' + x.handle), ' ', h('span', { class: 'pill' }, x.business ? 'business' : 'student'),
            h('div', { class: 'small' }, x.plan + ' - ' + new Date(x.startsAt).toLocaleDateString() + ' to ' + new Date(x.endsAt).toLocaleDateString()),
            h('div', { class: 'small muted' }, 'Order ' + x.reference));
        }, 'Nobody here yet.');
      }
      out.appendChild(tabs); out.appendChild(holder); show();
    }
    function planCard(p) {
      var price = h('input', { type: 'text', inputmode: 'decimal', value: (p.priceMinor / 100).toFixed(2), 'aria-label': 'Price in KES', size: 10 });
      var msg = h('div', { class: 'small', role: 'status' }), set = h('button', { class: 'btn ghost', type: 'button' }, 'Change price');
      set.addEventListener('click', function () {
        var minor = toMinor(price.value);
        if (minor == null) { msg.textContent = 'Enter the price as a plain number, for example 99 or 99.00.'; msg.className = 'small err'; return; }
        if (minor === p.priceMinor) { msg.textContent = 'That is already the price.'; return; }
        if (!window.confirm('Change the price of ' + p.name + ' from ' + kes(p.priceMinor) + ' to ' + kes(minor) + '? Orders already placed keep their price.')) return;
        set.disabled = true; api('PATCH', '/admin/monetization/plans/' + p.id, { priceMinor: minor, confirmPriceChange: true }).then(load).catch(function (e) { set.disabled = false; msg.textContent = e.message; msg.className = 'small err'; });
      });
      var toggle = confirmButton(p.active ? 'Take off sale' : 'Put on sale', (p.active ? 'Take ' : 'Put ') + p.name + (p.active ? ' off sale? People with it keep it until it ends.' : ' on sale? Students will see it and can start paying for it.'), function () { return api('PATCH', '/admin/monetization/plans/' + p.id, { active: !p.active }).then(load); }, p.active);
      return h('div', { class: 'card' },
        h('div', null, h('span', { class: 'pill' + (p.active ? ' teal' : '') }, p.active ? 'on sale' : 'not on sale'), h('span', { class: 'pill' }, p.audience === 'business' ? 'business' : 'student')),
        h('div', null, h('b', null, p.name), ' - ', kes(p.priceMinor), ' for ', p.periodDays + ' days, no automatic renewal'),
        h('div', { class: 'small muted' }, p.entitlements.length ? 'Includes: ' + p.entitlements.map(function (k) { return FEATURE_LABELS[k] || k; }).join(', ') : 'Includes no working features yet, so it cannot be put on sale.'),
        h('div', { class: 'small muted' }, p.liveSubscribers + ' subscribed now - code ' + p.code),
        h('div', { class: 'row' }, field('Price (KES)', price), set, toggle), msg);
  }
  load();
  }
`;
