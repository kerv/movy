const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage();
    await page.setContent('<button id="trailer">Play</button><div id="vp-shell"><button id="hd">1080p Full HD</button><button id="uhd">2160p</button></div>');
    await page.evaluate(() => { window.clicks = []; document.addEventListener('click', e => window.clicks.push(e.target.id)); });
    const source = fs.readFileSync('app/src/main/assets/native_resolver.js', 'utf8');
    await page.evaluate(source);
    await page.waitForTimeout(1000);
    assert.deepEqual(await page.evaluate(() => window.clicks), ['uhd']);
    await page.evaluate(() => JSON.parse(JSON.stringify({sources:[{url:'https://media.example/early.m3u8',quality:'1080p'}],subtitles:[{url:'https://media.example/early.vtt',lang:'eng'}]})));
    const cached = await page.evaluate(() => window.__movyNativeTracks);
    assert.equal(cached.subtitles[0].url, 'https://media.example/early.vtt');
    assert.equal(cached.sources[0].url, 'https://media.example/early.m3u8');
    console.log('PASS subtitle metadata can be pulled without a browser message bridge');
    await page.evaluate(() => {
      window.bundles = [];
      window.movyResolverBridge = { postMessage: message => window.bundles.push(message) };
      JSON.parse(JSON.stringify({ sources: [{ url: 'https://media.example/test.m3u8', quality: '2160p' }],
        subtitles: [{ url: 'https://media.example/english.vtt', lang: 'eng', language: 'eng' },
          { url: 'javascript:alert(1)', lang: 'bad' }], token: 'must-not-cross-the-bridge' }));
    });
    const bundle = JSON.parse(await page.evaluate(() => window.bundles[0]));
    assert.equal(bundle.subtitles.length, 1);
    assert.equal(bundle.subtitles[0].lang, 'eng');
    assert.equal(bundle.sources[0].label, '2160p');
    assert.equal(bundle.token, undefined);
    console.log('PASS resolver forwards advertised HTTPS subtitles and quality sources without unrelated response fields');
    await page.evaluate(source);
    await page.waitForTimeout(600);
    assert.deepEqual(await page.evaluate(() => window.clicks), ['uhd']);
    console.log('PASS resolver selects the highest watch rendition once, without clicking trailer/catalogue Play');
    for (const requested of ['Boise', 'Miami', 'Missing server']) {
      const fixture = await browser.newPage();
      await fixture.setContent('<button id="trailer">Play</button><div id="vp-shell"><button id="miami" class="bg-white/[0.14]"><img src="/flags/us.svg" alt="Miami">Miami</button><button id="boise"><img src="/flags/us.svg" alt="Boise">Boise</button><button id="uhd">2160p</button></div>');
      await fixture.evaluate(requested => {
        window.__movyNativeRequestedServer = requested; window.clicks = [];
        document.addEventListener('click', e => window.clicks.push(e.target.id));
        document.querySelector('#boise').addEventListener('click', () => {
          document.querySelector('#miami').className = ''; document.querySelector('#boise').className = 'bg-white/[0.14]';
          JSON.parse(JSON.stringify({sources:[{url:'https://boise.example/video.m3u8',quality:'2160p'}],subtitles:[{url:'https://boise.example/en.vtt',lang:'eng'}]}));
        });
      }, requested);
      await fixture.evaluate(source);
      await fixture.evaluate(() => JSON.parse(JSON.stringify({sources:[{url:'https://miami.example/video.m3u8',quality:'1080p'}],subtitles:[]})));
      await fixture.waitForTimeout(1100);
      const result = await fixture.evaluate(() => ({clicks:window.clicks,chosen:window.__movyNativeServerChosen,tracks:window.__movyNativeTracks,bundles:window.__movyNativeBundles}));
      if (requested === 'Boise') {
        assert.deepEqual(result.clicks,['boise','uhd']);assert.equal(result.tracks.server,'Boise');assert.equal(result.tracks.sources[0].url,'https://boise.example/video.m3u8');
        assert.equal(result.tracks.servers.length,2);assert.equal(result.bundles.length,1);
      } else if (requested === 'Miami') {assert.deepEqual(result.clicks,['uhd']);assert.equal(result.chosen,'Miami');}
      else {assert.deepEqual(result.clicks,[]);assert.equal(result.chosen,undefined);}
      await fixture.close();
    }
    console.log('PASS requested servers are selected before quality, default-server refresh works, and absent servers never silently fall back');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
