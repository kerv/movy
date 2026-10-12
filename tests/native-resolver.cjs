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
    await page.evaluate(source);
    await page.waitForTimeout(600);
    assert.deepEqual(await page.evaluate(() => window.clicks), ['uhd']);
    console.log('PASS resolver selects the highest watch rendition once, without clicking trailer/catalogue Play');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
