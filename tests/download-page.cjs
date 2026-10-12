const assert = require('node:assert/strict');
const fs = require('node:fs');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({headless:true});
  try {
    const page = await browser.newPage({viewport:{width:1280,height:900}});
    await page.route('https://downloads.example/**', route => {
      const path = new URL(route.request().url()).pathname;
      if(path === '/version.json') return route.fulfill({contentType:'application/json',body:JSON.stringify({versionName:'1.20',versionCode:21})});
      if(path === '/windows-version.json') return route.fulfill({contentType:'application/json',body:JSON.stringify({versionName:'1.20',preview:true})});
      return route.fulfill({contentType:'text/html',body:fs.readFileSync('web/index.html','utf8')});
    });
    await page.goto('https://downloads.example/');
    await page.waitForFunction(() => document.querySelector('#win-ver').textContent === 'v1.20 preview');
    assert.equal(await page.locator('#windows-download').getAttribute('href'),'movy-windows-x64.zip');
    assert.equal(await page.locator('#ver').textContent(),'1.20');
    assert.ok((await page.locator('body').textContent()).includes('unsigned preview'));
    assert.ok((await page.locator('body').textContent()).includes('Windows updates are manual'));
    assert.ok((await page.locator('body').textContent()).includes('WebView2 Runtime'));
    fs.mkdirSync('desktop/dist/previews',{recursive:true});
    await page.screenshot({path:'desktop/dist/previews/download-page.png',fullPage:true});
    console.log('PASS Android/Windows download links, separate manifests, prerequisites and preview labels');
  } finally {await browser.close();}
})().catch(error => {console.error(error);process.exit(1);});
