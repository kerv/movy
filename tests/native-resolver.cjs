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
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
