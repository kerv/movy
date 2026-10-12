const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    const token = 'header.' + Buffer.from(JSON.stringify({ sub: 'native-test' })).toString('base64url') + '.signature';
    const profiles = [{ id: 1, name: 'Primary', hasPin: false }, { id: 2, name: 'Protected', hasPin: true }];
    const calls = [];
    const headers = { 'Access-Control-Allow-Origin': 'https://www.movy.sx',
      'Access-Control-Allow-Headers': 'Authorization, Content-Type, X-Profile-Id',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS', 'Content-Type': 'application/json' };
    await page.route('https://backend.movy.sx/v1/**', async route => {
      const request = route.request();
      if (request.method() === 'OPTIONS') { await route.fulfill({ status: 204, headers }); return; }
      const path = new URL(request.url()).pathname.replace('/v1', '');
      calls.push({ path, profile: request.headers()['x-profile-id'] });
      let data;
      if (path === '/auth/login') data = { token, username: 'native-test' };
      else if (path === '/user/native-test') data = { username: 'native-test', email: 'test@example.invalid' };
      else if (path === '/user/native-test/profiles') data = profiles;
      else if (path.endsWith('/verify-pin')) {
        if (request.postDataJSON().pin !== '1234') { await route.fulfill({ status: 403, headers, body: '{}' }); return; }
        data = { valid: true };
      } else if (path.endsWith('/history')) data = { history: [{ id: 1399, name: 'Series', url: '/tv/1399', mediaType: 'tv', season: 2, currentEpisode: 3, currentTime: 123, totalDuration: 3000 },{id:10,name:'Completed series',mediaType:'tv',season:1,currentEpisode:10,currentTime:2995,totalDuration:3000},{id:11,title:'Completed movie',mediaType:'movie',currentTime:6999,totalDuration:7000}] };
      else if (path.endsWith('/lists')) data = [{ name: 'Weekend', items: [{ id: 603, title: 'The Matrix', mediaType: 'movie' }] }];
      else data = {};
      await route.fulfill({ status: 200, headers, body: JSON.stringify(data) });
    });
    await page.route('https://www.movy.sx/**', route => route.fulfill({ body: '<html><body></body></html>' }));
    await page.goto('https://www.movy.sx/');
    await page.evaluate(() => { window.messages = []; window.movyAccountBridge = { postMessage: message => window.messages.push(JSON.parse(message)) }; });
    await page.evaluate(fs.readFileSync('app/src/main/assets/native_account.js', 'utf8'));
    const request = async (action, args = {}) => page.evaluate(async ({ action, args }) => {
      await window.__movyAccountRequest({ id: window.messages.length + 1, action, args });
      return window.messages.at(-1);
    }, { action, args });
    assert.equal((await request('read')).snapshot.signedIn, false);
    assert.equal(calls.length, 0);
    const login = await request('login', { username: 'native-test', password: 'fixture-only' });
    assert.equal(login.snapshot.signedIn, true);
    assert.equal(login.snapshot.profile, null);
    const selected = await request('profile', { id: 1 });
    assert.equal(selected.snapshot.profile.name, 'Primary');
    const history = selected.snapshot.rows.find(row => row.name.startsWith('Continue Watching'));
    assert.equal(history.titles[0].slug, '/tv/1399/2/3');
    assert.equal(history.titles[0].resumeMs, 123000);
    assert.equal(history.titles[0].durationMs, 3000000);
    assert.equal(history.titles.length, 1);
    assert.equal(selected.snapshot.completedSeries.length, 1);
    assert.equal(selected.snapshot.completedSeries[0].slug, '/tv/10/1/10');
    assert.equal(selected.snapshot.completedSeries[0].resumeMs, 0);
    assert.equal(selected.snapshot.recentHistory.length, 3);
    assert.ok(selected.snapshot.rows.some(row => row.name === 'Weekend'));
    const denied = await request('profile', { id: 2, pin: '0000' });
    assert.equal(denied.ok, false);
    assert.equal((await request('read')).snapshot.profile.id, 1);
    assert.equal((await request('profile', { id: 2, pin: '1234' })).snapshot.profile.id, 2);
    assert.ok(calls.some(call => call.path.endsWith('/history') && call.profile === '2'));
    const messages = JSON.stringify(await page.evaluate(() => window.messages));
    assert.ok(!messages.includes(token));
    assert.ok(!messages.includes('fixture-only'));
    assert.equal((await request('logout')).snapshot.signedIn, false);
    console.log('PASS native account sign-in, profile-scoped lists/resume, PIN rejection, logout, and credential isolation');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
