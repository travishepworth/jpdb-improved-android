// Mock jpdb server + run the core end-to-end.
const assert = require('assert');
const { makeCore } = require('./jpdb-fast-review.user.js');

const NOW = 1_800_000_000_000;
const vocab = {
  '1,10': { spelling: '猫', reading: 'ねこ', meanings: ['cat'], pitch: ['HL'], state: ['due'], due: NOW / 1000 - 100 },
  '2,20': { spelling: '犬', reading: 'いぬ', meanings: ['dog'], pitch: ['LH'], state: ['failed'], due: NOW / 1000 - 999 },
  '3,30': { spelling: '鳥', reading: 'とり', meanings: ['bird'], pitch: ['LH'], state: ['known'], due: NOW / 1000 + 9999 },
  '4,40': { spelling: '魚', reading: 'さかな', meanings: ['fish'], pitch: ['LHH'], state: ['due'], due: NOW / 1000 - 5000 },
  '5,50': { spelling: '馬', reading: 'うま', meanings: ['horse'], pitch: ['LH'], state: ['redundant', 'due'], due: 0 },
  '6,60': { spelling: '牛', reading: 'うし', meanings: ['cow'], pitch: ['HL'], state: ['due'], due: NOW / 1000 + 50 }, // not yet due
};
const decks = { 7: ['1,10', '2,20', '3,30'], 8: ['3,30', '4,40', '5,50', '6,60'] };
let reviewNo = 100, posted = [], apiCalls = [], online = true, dropNextPostResponse = false;

const json = (o, status = 200) => ({ ok: status < 400, status, json: async () => o, text: async () => JSON.stringify(o) });
async function fetch(url, opt = {}) {
  if (!online) throw new TypeError('Failed to fetch');
  const u = new URL(url);
  if (u.pathname.startsWith('/api/v1/')) {
    const ep = u.pathname.slice(8), body = JSON.parse(opt.body);
    apiCalls.push(ep);
    assert.equal(opt.headers.Authorization, 'Bearer KEY');
    if (ep === 'list-user-decks') return json({ decks: Object.keys(decks).map(id => [+id, 'Deck ' + id]) });
    if (ep === 'deck/list-vocabulary') return json({ vocabulary: decks[body.id].map(k => k.split(',').map(Number)) });
    if (ep === 'lookup-vocabulary') {
      if (body.list.length > 3) return json({ error: 'bad_request', error_message: 'too big' }, 400); // force chunk halving path
      return json({ vocabulary_info: body.list.map(([v, s]) => { const x = vocab[v + ',' + s]; if (!x) return null;
        const f = { vid: v, sid: s, spelling: x.spelling, reading: x.reading, meanings: x.meanings, pitch_accent: x.pitch, part_of_speech: ['n'], frequency_rank: 500, card_state: x.state, due_at: x.due };
        return body.fields.map(k => f[k]); }) });
    }
  }
  if (u.pathname === '/review' && (!opt.method || opt.method === 'GET')) {
    return { ok: true, status: 200, text: async () => `<html><a href="/settings">s</a><form action="/review#a" method="post"><input type="hidden" name="c" value="vf,1,10"><input type="hidden" name="r" value="${reviewNo}"></form></html>` };
  }
  if (u.pathname === '/review' && opt.method === 'POST') {
    const p = Object.fromEntries(new URLSearchParams(opt.body));
    assert.equal(+p.r, reviewNo, 'stale review number');
    posted.push(p); reviewNo++;
    const [_, v, s] = p.c.split(',');
    vocab[v + ',' + s].state = p.g === 'f' ? ['failed'] : ['learning'];
    vocab[v + ',' + s].due = NOW / 1000 + 86400;
    if (dropNextPostResponse) { dropNextPostResponse = false; throw new TypeError('connection reset'); }
    return { ok: true, status: 200, text: async () => '' };
  }
  throw new Error('unexpected ' + url);
}

const mem = {}; const storage = { getItem: k => (k in mem ? mem[k] : null), setItem: (k, v) => { mem[k] = String(v); } };
let clock = NOW;
const sleep = ms => { clock += ms; return new Promise(r => setImmediate(r)); };
const mk = () => makeCore({ fetch, storage, now: () => clock, sleep, isOnline: () => online });
const drain = async c => { for (let i = 0; i < 10000 && c.outbox.length; i++) await new Promise(r => setImmediate(r)); };

(async () => {
  let core = mk();
  core.config.apiKey = 'KEY'; core.config.chunk = 8; core.saveConfig();
  await core.sync();
  assert.deepEqual(core.queue.map(c => c.vid), [4, 1, 2], 'due oldest-first, failed last, skip known/redundant/not-yet-due');
  assert.equal(core.contentOf(core.queue[0]).spelling, '魚');
  console.log('sync ok:', core.queue.map(c => core.contentOf(c).spelling).join(' '), '| api calls:', apiCalls.join(', '));

  // review while offline -> outbox persists
  online = false;
  core.grade(core.queue[0], 'pass');
  core.grade(core.queue[0], 'fail');
  await new Promise(r => setTimeout(r, 10));
  assert.equal(core.outbox.length, 2); assert.equal(core.queue.length, 1);
  assert.equal(posted.length, 0);

  // undo the last one (still in outbox)
  assert.equal(core.undo(), true);
  assert.equal(core.queue[0].vid, 1); assert.equal(core.outbox.length, 1);

  // "close the tab": new core instance from storage
  core = mk();
  assert.equal(core.outbox.length, 1); assert.equal(core.queue.length, 2);

  // back online; the first POST's response gets lost after the server applied it
  online = true; dropNextPostResponse = true;
  await core.flush();
  assert.equal(posted.length, 1, 'no double grade after lost response');
  assert.equal(core.outbox.length, 0);
  assert.deepEqual(posted[0], { c: 'vf,4,40', r: '100', g: 'p' });

  core.grade(core.queue[0], 'fail'); await drain(core);
  core.grade(core.queue[0], 'pass'); await drain(core);
  assert.deepEqual(posted.map(p => p.c + ':' + p.g), ['vf,4,40:p', 'vf,1,10:f', 'vf,2,20:p']);

  // resync: everything graded is no longer due
  apiCalls = [];
  await core.sync();
  assert.equal(core.queue.length, 0);
  assert.ok(!apiCalls.includes('list-user-decks'), 'deck list cached');
  console.log('resync api calls:', apiCalls.join(', '));

  // login check
  assert.throws(() => core.parseReviewNo('<a href="/login">Login</a>'), /Not logged in/);
  assert.equal(core.parseReviewNo(`<input value='7' type=hidden name=r>`), 7);
  console.log('ALL TESTS PASSED');
})().catch(e => { console.error(e); process.exit(1); });
