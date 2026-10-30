// ==UserScript==
// @name         jpdb fast review (prefetched / offline-tolerant)
// @namespace    jpdb-fast-review
// @version      0.1.0
// @description  Pulls your whole due queue in a few requests, reviews it locally with zero per-card loading, and sends grades back to jpdb in the background (queued + retried when the signal drops).
// @match        https://jpdb.io/*
// @run-at       document-start
// @grant        none
// ==/UserScript==

/*
 * HOW IT WORKS
 *
 *   Fetching the queue (official public API, Bearer key from jpdb settings page):
 *     1. POST /api/v1/list-user-decks          -> your deck ids               (cached 12h)
 *     2. POST /api/v1/deck/list-vocabulary     -> [vid, sid] pairs per deck   (cached 12h)
 *     3. POST /api/v1/lookup-vocabulary        -> card_state + due_at for every pair
 *        (small fields only, chunked), then a second lookup for the full card
 *        content of just the due ones (content cached forever per vid/sid).
 *
 *   Grading (private web endpoint, same one the review page / jpd-breader use;
 *   needs you logged in to jpdb in this browser):
 *     GET  /review?c=vf,<vid>,<sid>   -> scrape hidden <input name="r"> (review number)
 *     POST /review  c=vf,<vid>,<sid>&r=<n>&g=<grade>
 *   Grades go into a persistent outbox (localStorage) and are flushed one at a
 *   time with backoff, so you can keep reviewing with no signal at all.
 *
 * Open it at:  https://jpdb.io/learn#fast   (a ⚡ button is also added to /learn and /review)
 */

(function () {
    'use strict';

    // ---------------------------------------------------------------- core ---
    // Pure logic with injectable fetch/storage so it can be tested outside a browser.
    function makeCore(env) {
        const { fetch, storage, now = () => Date.now(), sleep = ms => new Promise(r => setTimeout(r, ms)), log = () => {} } = env;
        const BASE = 'https://jpdb.io';
        const K = k => 'jpdbFast:' + k;

        const load = (k, d) => { try { const v = storage.getItem(K(k)); return v == null ? d : JSON.parse(v); } catch { return d; } };
        const save = (k, v) => { try { storage.setItem(K(k), JSON.stringify(v)); } catch (e) { log('storage full?', e); } };

        const GRADES = { nothing: '1', something: '2', hard: '3', okay: '4', easy: '5', pass: 'p', fail: 'f' };
        const FAIL_GRADES = new Set(['nothing', 'something', 'fail']);
        const CONTENT_FIELDS = ['vid', 'sid', 'spelling', 'reading', 'meanings', 'pitch_accent', 'part_of_speech', 'frequency_rank'];

        const config = Object.assign({ apiKey: '', mode: 'passfail', deckTtlHours: 12, chunk: 1000 }, load('config', {}));
        let queue = load('queue', []);          // [{vid,sid,state,dueAt}] in review order
        let outbox = load('outbox', []);        // [{vid,sid,grade,t,r?,tries}]
        let content = load('content', {});      // "vid,sid" -> {spelling, reading, meanings, pitch, pos, freq}
        let lastSync = load('lastSync', 0);
        let lastGraded = null;                  // for undo
        const key = c => c.vid + ',' + c.sid;

        const listeners = new Set();
        const emit = (ev, data) => listeners.forEach(f => { try { f(ev, data); } catch {} });

        class ApiError extends Error { constructor(code, msg) { super(msg || code); this.code = code; } }
        class AuthError extends Error {}

        async function api(path, body, attempt = 0) {
            if (!config.apiKey) throw new ApiError('missing_key', 'Set your jpdb API key first (bottom of jpdb.io/settings).');
            const res = await fetch(`${BASE}/api/v1/${path}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: 'Bearer ' + config.apiKey },
                body: JSON.stringify(body),
            });
            if (res.status === 429 && attempt < 5) { await sleep(1000 * 2 ** attempt); return api(path, body, attempt + 1); }
            const data = await res.json().catch(() => ({}));
            if (!res.ok || data.error) throw new ApiError(data.error || 'http_' + res.status, data.error_message);
            return data;
        }

        async function lookup(pairs, fields) {
            const out = [];
            let size = config.chunk;
            for (let i = 0; i < pairs.length;) {
                const slice = pairs.slice(i, i + size);
                try {
                    const d = await api('lookup-vocabulary', { list: slice, fields });
                    out.push(...d.vocabulary_info);
                    i += slice.length;
                    emit('progress', `checked ${Math.min(i, pairs.length)}/${pairs.length} cards`);
                    await sleep(200);
                } catch (e) {
                    if (e.code === 'bad_request' && size > 1) { size = Math.floor(size / 2); config.chunk = size; continue; } // probably too big
                    throw e;
                }
            }
            return out;
        }

        async function allPairs(force) {
            const cached = load('pairs', null);
            if (!force && cached && now() - cached.t < config.deckTtlHours * 3600e3) return cached.pairs;
            emit('progress', 'listing decks');
            const decks = (await api('list-user-decks', { fields: ['id', 'name'] })).decks || [];
            const seen = new Set(), pairs = [];
            for (const [id, name] of decks) {
                emit('progress', `reading deck “${name}”`);
                const d = await api('deck/list-vocabulary', { id, fetch_occurences: false });
                for (const p of d.vocabulary || []) { const k = p[0] + ',' + p[1]; if (!seen.has(k)) { seen.add(k); pairs.push([p[0], p[1]]); } }
                await sleep(200);
            }
            save('pairs', { t: now(), pairs });
            return pairs;
        }

        // card_state is e.g. ["due"], ["failed"], ["redundant","due"], ["locked","due"]
        const isReviewable = st => Array.isArray(st) && st.length === 1 && (st[0] === 'due' || st[0] === 'failed');
        const toMs = t => (t == null ? 0 : t < 1e12 ? t * 1000 : t);

        let syncing = false, flushing = false;

        async function sync({ forceDecks = false } = {}) {
            if (syncing) return;
            syncing = true; emit('sync', 'start');
            try {
                while (flushing) await sleep(100); // flusher yields when it sees `syncing`; don't race it (would re-add just-graded cards)
                const pairs = await allPairs(forceDecks);
                const states = await lookup(pairs, ['vid', 'sid', 'card_state', 'due_at']);
                const pending = new Set(outbox.map(key));
                const t = now();
                let due = states
                    .filter(v => v && isReviewable(v[2]) && toMs(v[3]) <= t)
                    .map(v => ({ vid: v[0], sid: v[1], state: v[2][0], dueAt: toMs(v[3]) }))
                    .filter(c => !pending.has(key(c)));
                // failed cards after due ones, each group oldest-due first
                due.sort((a, b) => (a.state === 'failed') - (b.state === 'failed') || a.dueAt - b.dueAt);

                const missing = due.filter(c => !content[key(c)]).map(c => [c.vid, c.sid]);
                if (missing.length) {
                    emit('progress', `downloading ${missing.length} cards`);
                    for (const v of await lookup(missing, CONTENT_FIELDS)) {
                        if (!v) continue;
                        content[v[0] + ',' + v[1]] = { spelling: v[2], reading: v[3], meanings: v[4], pitch: v[5], pos: v[6], freq: v[7] };
                    }
                    save('content', content);
                }
                queue = due.filter(c => content[key(c)]);
                lastSync = now();
                save('queue', queue); save('lastSync', lastSync);
                emit('sync', 'done');
            } catch (e) {
                emit('sync', 'error'); emit('error', e);
                throw e;
            } finally { syncing = false; flush(); }
        }

        function grade(card, g) {
            if (!(g in GRADES)) throw new Error('bad grade ' + g);
            queue = queue.filter(c => key(c) !== key(card));
            const item = { vid: card.vid, sid: card.sid, grade: g, t: now(), tries: 0 };
            outbox.push(item);
            lastGraded = { card, item };
            save('queue', queue); save('outbox', outbox);
            emit('change');
            flush();
        }

        function undo() {
            if (!lastGraded) return false;
            const idx = outbox.indexOf(lastGraded.item);
            if (idx < 0) return false; // already sent, too late
            outbox.splice(idx, 1);
            queue.unshift(lastGraded.card);
            lastGraded = null;
            save('queue', queue); save('outbox', outbox);
            emit('change');
            return true;
        }

        function parseReviewNo(html) {
            if (/href="\/login"/.test(html) && !/name="?r"?/.test(html)) throw new AuthError('Not logged in to jpdb.io in this browser — log in, then it will resume.');
            for (const tag of html.match(/<input\b[^>]*>/gi) || []) {
                if (/\bname\s*=\s*["']?r["'\s>]/i.test(tag)) {
                    const m = tag.match(/\bvalue\s*=\s*["']?(\d+)/i);
                    if (m) return parseInt(m[1], 10);
                }
            }
            throw new Error('Could not find review number on the review page (jpdb changed its HTML?)');
        }

        async function getReviewNo(it) {
            const res = await fetch(`${BASE}/review?c=vf%2C${it.vid}%2C${it.sid}`, { credentials: 'include' });
            if (!res.ok) throw new Error('HTTP ' + res.status);
            return parseReviewNo(await res.text());
        }

        async function sendOne(it) {
            const r = await getReviewNo(it);
            // If a previous POST went through but we lost the response, the review number moved on: don't double-grade.
            if (it.r != null && r > it.r) return;
            it.r = r; save('outbox', outbox);
            await sleep(1100);
            const res = await fetch(`${BASE}/review`, {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: `c=vf%2C${it.vid}%2C${it.sid}&r=${r}&g=${GRADES[it.grade]}`,
            });
            if (res.status >= 400) throw new Error('HTTP ' + res.status);
        }

        async function flush() {
            if (flushing || syncing) return;
            flushing = true;
            let backoff = 2000;
            try {
                while (outbox.length && !syncing) {
                    if (env.isOnline && !env.isOnline()) { emit('net', 'offline'); break; }
                    // keep the very latest grade for a few seconds so undo stays possible
                    const it = outbox[0];
                    if (lastGraded && lastGraded.item === it && now() - it.t < 4000) { await sleep(4000 - (now() - it.t)); continue; }
                    try {
                        await sendOne(it);
                        outbox.shift();
                        if (lastGraded && lastGraded.item === it) lastGraded = null;
                        save('outbox', outbox); emit('change'); emit('net', 'ok');
                        backoff = 2000;
                        await sleep(1100);
                    } catch (e) {
                        if (e instanceof AuthError) { emit('error', e); break; }
                        it.tries++; save('outbox', outbox);
                        emit('net', 'retrying'); log('flush failed, retrying', e);
                        for (let w = 0; w < backoff && !syncing; w += 250) await sleep(250);
                        backoff = Math.min(backoff * 2, 60000);
                    }
                }
            } finally { flushing = false; }
        }

        return {
            config, GRADES, FAIL_GRADES,
            saveConfig: () => save('config', config),
            get queue() { return queue; },
            get outbox() { return outbox; },
            get lastSync() { return lastSync; },
            get busy() { return syncing; },
            contentOf: c => content[key(c)],
            sync, grade, undo, flush, parseReviewNo,
            on: f => listeners.add(f),
        };
    }

    if (typeof module !== 'undefined' && module.exports) { module.exports = { makeCore }; return; }
    if (typeof location === 'undefined' || location.host !== 'jpdb.io') return;

    // ------------------------------------------------------------------ UI ---
    if (location.hash !== '#fast') {
        const addButton = () => {
            if (!/^\/(learn|review)/.test(location.pathname) || document.getElementById('jf-launch')) return;
            const a = document.createElement('a');
            a.id = 'jf-launch'; a.href = '/learn#fast'; a.textContent = '⚡';
            a.title = 'Fast review (prefetched)';
            a.style.cssText = 'position:fixed;right:14px;bottom:14px;z-index:9999;width:48px;height:48px;border-radius:50%;background:#4b8dff;color:#fff;font-size:24px;display:flex;align-items:center;justify-content:center;text-decoration:none;box-shadow:0 2px 10px #0006';
            a.addEventListener('click', e => { e.preventDefault(); location.hash = 'fast'; location.reload(); });
            document.body.appendChild(a);
        };
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', addButton); else addButton();
        window.addEventListener('hashchange', () => { if (location.hash === '#fast') location.reload(); });
        return;
    }

    if (document.readyState === 'loading') { try { window.stop(); } catch {} } // don't bother downloading the real page

    const core = makeCore({ fetch: window.fetch.bind(window), storage: window.localStorage, isOnline: () => navigator.onLine, log: (...a) => console.log('[jpdb-fast]', ...a) });

    const CSS = `
    :root{--bg:#181818;--fg:#e8e8e8;--dim:#9a9a9a;--card:#232323;--line:#333;--acc:#4b8dff;--bad:#e5534b;--good:#3fb950;--warn:#d29922}
    @media (prefers-color-scheme: light){:root{--bg:#f6f6f6;--fg:#1c1c1c;--dim:#666;--card:#fff;--line:#ddd}}
    *{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);font:16px/1.4 system-ui,-apple-system,"Hiragino Sans","Noto Sans JP",sans-serif;-webkit-tap-highlight-color:transparent}
    #jf{display:flex;flex-direction:column;height:100dvh;max-width:640px;margin:0 auto}
    header{display:flex;align-items:center;gap:10px;padding:10px 14px;border-bottom:1px solid var(--line);font-size:14px}
    header .n{font-weight:600}header .grow{flex:1}
    .dot{width:9px;height:9px;border-radius:50%;background:var(--dim);display:inline-block;margin-right:4px}
    .dot.ok{background:var(--good)}.dot.off{background:var(--bad)}.dot.retry{background:var(--warn)}
    button{font:inherit;color:inherit;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:8px 12px;cursor:pointer}
    button:disabled{opacity:.4}
    main{flex:1;display:flex;flex-direction:column;justify-content:center;align-items:center;padding:18px;text-align:center;overflow:auto;cursor:pointer}
    .word{font-size:clamp(40px,13vw,72px);font-weight:500;letter-spacing:.02em}
    .reading{font-size:24px;margin-top:6px}
    .mora{display:inline-block;padding-top:3px}.mora.h{border-top:2px solid var(--acc)}.mora.drop{border-right:2px solid var(--acc)}
    .meanings{margin:18px 0 0;padding:0;list-style:none;max-width:520px;text-align:left}
    .meanings li{padding:6px 0;border-top:1px solid var(--line)}.meanings .pos{color:var(--dim);font-size:12px;display:block}
    .meta{color:var(--dim);font-size:13px;margin-top:10px}
    .hint{color:var(--dim);margin-top:24px;font-size:14px}
    footer{display:grid;gap:8px;padding:10px 12px calc(10px + env(safe-area-inset-bottom));border-top:1px solid var(--line)}
    footer.pf{grid-template-columns:1fr 1fr}footer.five{grid-template-columns:repeat(5,1fr)}
    footer button{padding:16px 4px;font-size:16px;font-weight:600}
    .fail{background:color-mix(in srgb,var(--bad) 22%,var(--card))}.pass{background:color-mix(in srgb,var(--good) 22%,var(--card))}
    .reveal{grid-column:1/-1;background:var(--acc);color:#fff;border:0}
    .msg{color:var(--dim);max-width:420px}.err{color:var(--bad)}
    dialog{background:var(--card);color:var(--fg);border:1px solid var(--line);border-radius:14px;width:min(92vw,420px)}
    dialog label{display:block;margin:10px 0 4px;font-size:14px;color:var(--dim)}
    dialog input,dialog select{width:100%;font:inherit;padding:8px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--fg)}
    dialog .row{display:flex;gap:8px;justify-content:flex-end;margin-top:16px}
    `;

    function el(tag, attrs = {}, ...kids) {
        const e = document.createElement(tag);
        for (const [k, v] of Object.entries(attrs)) { if (k === 'class') e.className = v; else if (k.startsWith('on')) e.addEventListener(k.slice(2), v); else e.setAttribute(k, v); }
        for (const c of kids.flat()) if (c != null) e.append(c);
        return e;
    }

    const SMALL = new Set('ゃゅょぁぃぅぇぉゎャュョァィゥェォヮ');
    function moras(s) { const out = []; for (const ch of s) { if (SMALL.has(ch) && out.length) out[out.length - 1] += ch; else out.push(ch); } return out; }
    function renderReading(reading, pitch) {
        const pat = Array.isArray(pitch) && pitch[0];
        const m = moras(reading || '');
        if (!pat || !/^[HL]+$/.test(pat) || pat.length < m.length) return el('div', { class: 'reading' }, reading);
        return el('div', { class: 'reading' }, m.map((x, i) => el('span', { class: 'mora' + (pat[i] === 'H' ? ' h' : '') + (pat[i] === 'H' && pat[i + 1] === 'L' ? ' drop' : '') }, x)));
    }

    let revealed = false;
    let status = { net: navigator.onLine ? 'ok' : 'off', msg: '', err: '' };

    let booted = false;
    function boot() {
        if (booted) return;
        booted = true;
        document.documentElement.replaceChildren(
            el('head', {}, el('meta', { charset: 'utf-8' }), el('meta', { name: 'viewport', content: 'width=device-width,initial-scale=1,viewport-fit=cover' }), el('title', {}, 'jpdb ⚡ review'), el('style', {}, CSS)),
            el('body', {}, el('div', { id: 'jf' })),
        );
        core.on((ev, d) => {
            if (ev === 'progress') status.msg = d;
            if (ev === 'sync') status.msg = d === 'start' ? 'syncing…' : '';
            if (ev === 'net') status.net = d === 'ok' ? 'ok' : d === 'offline' ? 'off' : 'retry';
            if (ev === 'error') status.err = d.message;
            if (ev === 'change' || ev === 'sync') status.err = ev === 'sync' && d === 'error' ? status.err : '';
            render();
        });
        window.addEventListener('online', () => { status.net = 'ok'; core.flush(); render(); });
        window.addEventListener('offline', () => { status.net = 'off'; render(); });
        document.addEventListener('keydown', onKey);
        render();
        if (!core.config.apiKey) return openSettings();
        core.flush();
        if (navigator.onLine && (Date.now() - core.lastSync > 10 * 60e3 || core.queue.length === 0)) doSync();
    }

    function doSync(force) { status.err = ''; core.sync({ forceDecks: force }).catch(() => {}).finally(render); render(); }

    function onKey(e) {
        if (document.querySelector('dialog[open]')) return;
        const c = core.queue[0]; if (!c) return;
        if (!revealed) { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); revealed = true; render(); } return; }
        const map = core.config.mode === 'five' ? { 1: 'nothing', 2: 'something', 3: 'hard', 4: 'okay', 5: 'easy' } : { 1: 'fail', f: 'fail', 2: 'pass', p: 'pass', ' ': 'pass' };
        if (map[e.key]) { e.preventDefault(); doGrade(c, map[e.key]); }
        if (e.key === 'z' || e.key === 'u') doUndo();
    }

    function doGrade(card, g) { revealed = false; core.grade(card, g); }
    function doUndo() { if (core.undo()) { revealed = true; render(); } }

    function render() {
        const root = document.getElementById('jf'); if (!root) return;
        const c = core.queue[0];
        const pending = core.outbox.length;
        const syncBtn = el('button', { onclick: () => doSync(false), title: 'Fetch due cards from jpdb' }, core.busy ? '…' : '⟳');
        syncBtn.disabled = core.busy;
        const netLabel = status.net === 'ok' ? (pending ? 'sending' : 'synced') : status.net === 'off' ? 'offline' : 'retrying';

        const header = el('header', {},
            el('span', { class: 'n' }, `${core.queue.length} due`),
            el('span', {}, el('span', { class: 'dot ' + (status.net === 'ok' ? 'ok' : status.net === 'off' ? 'off' : 'retry') }), `${netLabel}${pending ? ` · ${pending} queued` : ''}`),
            el('span', { class: 'grow' }),
            el('button', { onclick: doUndo, title: 'Undo last grade (before it is sent)' }, '↶'),
            syncBtn,
            el('button', { onclick: openSettings }, '⚙'),
        );

        let main, footer;
        if (!c) {
            main = el('main', {}, el('div', { class: 'msg' },
                status.err ? el('div', { class: 'err' }, status.err) : null,
                core.busy ? (status.msg || 'syncing…') : core.lastSync ? 'No due cards in the local queue. Tap ⟳ to check jpdb for more.' : 'Tap ⟳ to download your due cards.'));
            footer = el('footer', { class: 'pf' });
        } else {
            const ct = core.contentOf(c) || {};
            const kids = [el('div', { class: 'word' }, ct.spelling || '?')];
            if (revealed) {
                if (ct.reading && ct.reading !== ct.spelling) kids.push(renderReading(ct.reading, ct.pitch));
                else if (ct.pitch) kids.push(renderReading(ct.reading || ct.spelling, ct.pitch));
                const posList = ct.pos || [];
                kids.push(el('ol', { class: 'meanings' }, (ct.meanings || []).map((m, i) => el('li', {}, posList.length && i === 0 ? el('span', { class: 'pos' }, posList.join(', ')) : null, `${i + 1}. ${m}`))));
                kids.push(el('div', { class: 'meta' }, [c.state === 'failed' ? 'previously failed' : null, ct.freq ? `top ${ct.freq}` : null].filter(Boolean).join(' · ')));
            } else kids.push(el('div', { class: 'hint' }, 'tap to reveal'));
            if (status.err) kids.push(el('div', { class: 'err meta' }, status.err));
            main = el('main', { onclick: () => { if (!revealed) { revealed = true; render(); } } }, kids);

            if (!revealed) footer = el('footer', { class: 'pf' }, el('button', { class: 'reveal', onclick: () => { revealed = true; render(); } }, 'Show answer'));
            else if (core.config.mode === 'five') footer = el('footer', { class: 'five' },
                [['nothing', 'Nothing'], ['something', 'Something'], ['hard', 'Hard'], ['okay', 'Okay'], ['easy', 'Easy']].map(([g, l], i) =>
                    el('button', { class: i < 2 ? 'fail' : 'pass', onclick: () => doGrade(c, g) }, l)));
            else footer = el('footer', { class: 'pf' }, el('button', { class: 'fail', onclick: () => doGrade(c, 'fail') }, '✘ Fail'), el('button', { class: 'pass', onclick: () => doGrade(c, 'pass') }, '✔ Pass'));
        }
        root.replaceChildren(header, main, footer);
    }

    function openSettings() {
        const cfg = core.config;
        const key = el('input', { type: 'password', value: cfg.apiKey, placeholder: 'from the bottom of jpdb.io/settings', autocomplete: 'off' });
        const mode = el('select', {}, el('option', { value: 'passfail' }, 'Pass / Fail'), el('option', { value: 'five' }, '5 grades (Nothing … Easy)'));
        mode.value = cfg.mode;
        const ttl = el('input', { type: 'number', min: '0', value: String(cfg.deckTtlHours) });
        const dlg = el('dialog', {},
            el('h3', { style: 'margin:0' }, 'Settings'),
            el('label', {}, 'jpdb API key'), key,
            el('label', {}, 'Grading buttons (match your jpdb setting)'), mode,
            el('label', {}, 'Re-read deck contents every N hours (0 = every sync)'), ttl,
            el('div', { class: 'meta', style: 'text-align:left' }, 'Grades are sent using your normal jpdb login in this browser, so stay logged in.'),
            el('div', { class: 'row' },
                el('button', { onclick: () => { dlg.close(); dlg.remove(); doSync(true); } }, 'Full resync'),
                el('button', { onclick: () => {
                    cfg.apiKey = key.value.trim(); cfg.mode = mode.value; cfg.deckTtlHours = Math.max(0, +ttl.value || 0);
                    core.saveConfig(); dlg.close(); dlg.remove(); render();
                    if (cfg.apiKey && !core.queue.length) doSync();
                } }, 'Save')),
        );
        document.body.append(dlg);
        dlg.showModal();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot, { once: true }); else boot();
    // window.stop() can leave readyState stuck at "loading" with no DOMContentLoaded; make sure we still boot
    setTimeout(boot, 300);
})();
