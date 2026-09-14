// Deterministic regression cases for the actual site structures. No live
// catalogue, advertising, accounts or video provider needed for this suite.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const path = require('node:path');

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined,
    args: ['--no-sandbox']
  });
  try {
    const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
    await context.addInitScript({ path: path.resolve(__dirname, '../app/src/main/assets/tv_enhance.js') });
    await context.route('https://www.movy.sx/**', route => route.fulfill({contentType: 'text/html', body: `<!doctype html>
      <style>body{margin:20px}button,a,input{display:inline-block;padding:12px;margin:8px}
      .row{display:flex;overflow-x:auto;width:450px}.row a{flex:0 0 180px}
      #vp-shell{position:fixed;inset:0;background:black;color:white}
      video{position:absolute;inset:0;width:100%;height:100%}
      .header-top{position:absolute;top:0;right:0}
      .bottom-0{position:absolute;bottom:0;left:0}
      .player-surface{background:#333;padding:10px}
      .closed{opacity:0;pointer-events:none}
      </style>
      <a id="home" href="/">Home</a><a id="nested" href="#details"><button>Details</button></a>
      <input id="search" placeholder="Search"><button id="disabled" disabled>Disabled</button>
      <div style="opacity:0"><button id="hidden">Hidden</button></div>
      <div class="row">${Array.from({length:8},(_,i)=>`<a id="card${i}" href="#card${i}">Card ${i}</a>`).join('')}</div>
      <button id="below" style="margin-top:850px">Below the fold</button>
      <script>window.shortcutCount=0;window.clickCount=0;
      window.addEventListener('keyup',e=>{if(['ArrowRight','ArrowLeft','ArrowUp','ArrowDown',' '].includes(e.key))window.shortcutCount++});
      document.querySelector('#nested').addEventListener('click',e=>{e.preventDefault();window.clickCount++});
      </script>`}));
    const page = await context.newPage();
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    const active = () => page.evaluate(() => document.activeElement.id);
    await page.goto('https://www.movy.sx/');
    await page.waitForTimeout(100);
    assert.equal(await page.evaluate(() => sessionStorage.getItem('ads-enabled-session')), 'false');
    await page.locator('#home').focus();
    await page.keyboard.press('ArrowRight');
    assert.equal(await active(), 'nested');
    await page.keyboard.press('Enter');
    assert.equal(await page.evaluate(() => clickCount), 1, 'OK activates a nested link once');
    await page.keyboard.press('ArrowRight');
    assert.equal(await active(), 'search', 'hidden/disabled controls are excluded');
    await page.keyboard.type('some title');
    assert.equal(await page.locator('#search').inputValue(), 'some title');
    await page.keyboard.press('ArrowLeft');
    assert.equal(await active(), 'search', 'text cursor keeps left/right');
    await page.locator('#card0').focus();
    for (let i=0;i<6;i++) await page.keyboard.press('ArrowRight');
    assert.equal(await active(), 'card6');
    assert.ok(await page.locator('.row').evaluate(el => el.scrollLeft) > 0, 'remote scrolls carousel');
    await page.locator('#card6').evaluate(el => el.replaceWith(el.cloneNode(true)));
    await page.waitForTimeout(100);
    assert.equal(await active(), 'card6', 'virtualized card replacement restores focus');
    await page.keyboard.press('ArrowDown');
    assert.equal(await active(), 'below');
    assert.ok(await page.evaluate(() => scrollY) > 0, 'remote reaches below-fold content');
    assert.equal(await page.evaluate(() => shortcutCount), 0, 'navigation does not also seek on keyup');
    console.log('PASS catalogue focus, nested OK, editing, carousel, virtualization, vertical scrolling');

    await page.evaluate(() => {
      scrollTo(0,0);
      const ad = document.createElement('iframe');ad.id='qr-ad';document.body.appendChild(ad);
      ad.contentDocument.body.innerHTML="<div>Confirm you're not a robot</div><div>Scan the qr-code with your phone</div>";
      const legitimate = document.createElement('div');legitimate.id='legitimate';
      legitimate.style.cssText='position:fixed;background:white;inset:0;z-index:10000';
      legitimate.innerHTML='<button>×</button><p>Choose a server</p>';
      document.body.appendChild(legitimate);
      const captcha=document.createElement('iframe');captcha.id='real-captcha';captcha.src='about:blank';document.body.appendChild(captcha);
      const videoFrame=document.createElement('iframe');videoFrame.id='trusted-player';videoFrame.src='https://www.vidy.st/embed/test';document.body.appendChild(videoFrame);
    });
    await page.waitForTimeout(1700);
    assert.equal(await page.locator('#qr-ad').count(), 0);
    assert.equal(await page.locator('#legitimate').count(), 1);
    assert.equal(await page.locator('#real-captcha').count(), 1);
    assert.equal(await page.locator('#trusted-player').count(), 1);
    console.log('PASS exact blank-frame QR ad removed; dialogs, real challenges and player preserved');

    await page.evaluate(() => {
      document.body.innerHTML=`<div id="vp-shell"><div class="vp-container"><video></video>
      <div class="header-top"><button id="menu-one" class="tabbable">Quality</button>
      <div id="options" class="closed"><div class="player-surface"><button id="q720"><span>720p</span><span>HD</span></button><button id="q1080"><span>1080p</span><span>Full HD</span></button></div></div></div>
      <div class="transport-layout"><div class="transport-fade" aria-hidden="true" style="opacity:0;transform:translateY(8px);pointer-events:none"><button id="ButtonPlay">Play</button><button id="rewind">Rewind</button><button id="forward">Forward</button></div></div></div></div>`;
      let paused=false,time=50,sitePaused=false;
      const v=document.querySelector('video');
      Object.defineProperties(v,{paused:{get:()=>paused},duration:{get:()=>100},currentTime:{get:()=>time,set:t=>time=t}});
      v.play=()=>{paused=false;v.dispatchEvent(new Event('play'));return Promise.resolve()};
      v.pause=()=>{paused=true;v.dispatchEvent(new Event('pause'))};
      document.querySelector('#ButtonPlay').onclick=()=>{
        sitePaused=!sitePaused;
        document.querySelector('#ButtonPlay').textContent=sitePaused?'Resume':'Pause';
        return sitePaused?v.pause():v.play();
      };
      document.querySelector('#menu-one').onclick=()=>document.querySelector('#options').classList.toggle('closed');
      document.querySelector('#q1080').onclick=()=>{
        v.remove();
        setTimeout(()=>{sitePaused=false;document.querySelector('.vp-container').prepend(v);v.play()},50);
      };
      window.addEventListener('keydown',e=>{if(e.key==='Escape')document.querySelector('#options').classList.add('closed')});
    });
    await page.waitForTimeout(100);
    await page.keyboard.press('Enter');
    assert.equal(await active(), 'ButtonPlay');
    assert.equal(await page.locator('video').evaluate(v=>v.paused), true, 'first OK pauses exactly once');
    assert.equal(await page.locator('#ButtonPlay').innerText(),'Resume','site control state matches paused video');
    await page.keyboard.press('ArrowRight');
    assert.equal(await active(), 'rewind');
    assert.equal(await page.locator('video').evaluate(v=>v.currentTime), 50, 'arrows navigate without seeking');
    await page.locator('#menu-one').focus();
    await page.keyboard.press('Enter');
    await page.waitForTimeout(220);
    assert.equal(await active(), 'q720', 'OK opens quality, not refocus video');
    await page.keyboard.press('ArrowRight');
    assert.equal(await active(), 'q1080');
    await page.waitForTimeout(1800);
    assert.equal(await active(), 'q1080', 'no periodic focus stealing');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(450);
    assert.equal(await active(), 'menu-one', 'quality selection closes menu and restores trigger');
    assert.equal(await page.locator('video').evaluate(v=>v.paused),true,'source-change autoplay must preserve requested pause');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(230);
    assert.equal(await page.evaluate(()=>window.__movyTV.back()), true);
    await page.waitForTimeout(220);
    assert.equal(await active(), 'menu-one', 'Back closes popup and restores trigger');
    await page.evaluate(()=>{
      const button=document.createElement('button');button.id='menu-two';button.className='tabbable';
      button.textContent='Audio & Subtitles';document.querySelector('.header-top').appendChild(button);
      button.onclick=()=>{
        const p=document.createElement('div');p.id='audio-panel';p.className='player-surface';
        p.innerHTML='<button id="sub-off">Off</button><button id="sub-en">English</button>';
        document.querySelector('.header-top').appendChild(p);
      };
      window.addEventListener('keydown',e=>{if(e.key==='Escape')document.querySelector('#audio-panel')?.remove()});
    });
    await page.locator('#menu-two').focus();await page.keyboard.press('Enter');await page.waitForTimeout(230);
    assert.equal(await active(),'sub-off','generic subtitle button opens its menu');
    await page.keyboard.press('ArrowRight');assert.equal(await active(),'sub-en');
    assert.equal(await page.evaluate(()=>window.__movyTV.back()),true);await page.waitForTimeout(230);
    assert.equal(await active(),'menu-two');

    // Exercise the actual fade -> pause -> wait -> resume sequence; the old
    // tests never waited for either the site's controls or our timer to fade.
    await page.clock.install();
    await page.evaluate(()=>window.__movyTV.media('play'));
    await page.clock.runFor(5200);
    assert.equal(await page.locator('#vp-shell').getAttribute('data-movy-controls'),null);
    await page.keyboard.press('Enter');
    assert.equal(await page.locator('video').evaluate(v=>v.paused),true);
    assert.equal(await active(),'ButtonPlay');
    assert.equal(await page.locator('.transport-fade').getAttribute('data-movy-transport'),'true','paused controls reveal a transport wrapper even when the site changes its bottom-row classes');
    assert.equal(await page.locator('.transport-fade').evaluate(el=>getComputedStyle(el).opacity),'1','transport controls are visually available after idle pause');
    await page.clock.runFor(11000);
    assert.equal(await page.locator('#vp-shell').getAttribute('data-movy-controls'),'true','pause pins controls beyond the fade timeout');
    assert.equal(await active(),'ButtonPlay');
    await page.keyboard.press('Enter');
    assert.equal(await page.locator('video').evaluate(v=>v.paused),false,'second OK resumes');
    await page.clock.runFor(5200);
    // Mobile controls are removed, not merely transparent. Only an event
    // reaching the inner video surface tells the site to mount them again.
    await page.evaluate(()=>{
      const video=document.querySelector('video');
      const surface=document.createElement('div');
      video.before(surface);surface.append(video);
      const transport=document.querySelector('.transport-layout');
      transport.remove();
      surface.addEventListener('touchmove',()=>setTimeout(()=>{
        if (!transport.isConnected) document.querySelector('.vp-container').append(transport);
      },20));
    });
    await page.keyboard.press('Enter');
    await page.clock.runFor(120);
    assert.equal(await page.locator('video').evaluate(v=>v.paused),true,'idle OK wakes unmounted mobile controls and pauses');
    assert.equal(await active(),'ButtonPlay','remounted Resume receives focus without opening Quality');
    await page.clock.runFor(6500);
    assert.equal(await active(),'ButtonPlay');
    await page.keyboard.press('ArrowRight');
    assert.equal(await active(),'rewind','transport navigation works after mobile wake');
    await page.locator('#ButtonPlay').focus();
    await page.keyboard.press('Enter');
    assert.equal(await page.locator('video').evaluate(v=>v.paused),false,'OK resumes after mobile wake');
    await page.clock.runFor(5200);
    await page.evaluate(()=>{
      window.savedPlay=document.querySelector('#ButtonPlay');window.savedPlay.remove();
      const exit=document.createElement('button');exit.id='exit-show';exit.textContent='Back';
      exit.onclick=()=>window.exited=true;document.querySelector('.header-top').prepend(exit);
    });
    await page.keyboard.press('Enter');await page.clock.runFor(50);
    assert.notEqual(await active(),'exit-show','missing play control never falls back to Back');
    await page.evaluate(()=>document.querySelector('.transport-fade').prepend(window.savedPlay));
    await page.clock.runFor(80);
    assert.equal(await active(),'ButtonPlay','lazy play control receives pending focus');
    await page.keyboard.press('Enter');
    assert.equal(await page.evaluate(()=>!!window.exited),false);
    assert.equal(await page.locator('video').evaluate(v=>v.paused),false,'lazy control resumes with synchronized site state');
    await page.locator('#ButtonPlay').evaluate(el=>{
      const replacement=el.cloneNode(true);replacement.onclick=el.onclick;el.replaceWith(replacement);
    });
    await page.clock.runFor(80);
    assert.equal(await active(),'ButtonPlay','re-rendered play control keeps focus');
    await page.clock.resume();
    console.log('PASS unlabeled menus, idle pause/resume, pinned controls, lazy/replaced play button');
    assert.deepEqual(await page.evaluate(()=>{
      const api=window.__movyTV,v=document.querySelector('video');
      api.media('pause');api.media('pause');const pause=v.paused;
      api.media('play');api.media('play');const play=!v.paused;
      api.media('rewind');const rewind=v.currentTime;
      api.media('forward');const forward=v.currentTime;
      for(let i=0;i<20;i++)api.media('forward');const end=v.currentTime;
      for(let i=0;i<20;i++)api.media('rewind');
      return {pause,play,rewind,forward,end,start:v.currentTime};
    }), {pause:true,play:true,rewind:40,forward:50,end:100,start:0});
    assert.equal(await page.evaluate(()=>shortcutCount), 0);
    await page.evaluate(()=>{
      const seek=document.createElement('div');seek.id='seek';seek.className='cursor-pointer';
      seek.style.cssText='position:absolute;left:50px;right:50px;bottom:180px;height:36px';
      seek.innerHTML='<div class="videoSeekBar-module__test__seekLeft"></div>';
      document.querySelector('.vp-container').append(seek);
      document.querySelector('video').currentTime=50;
    });
    // Slider semantics are applied by the observer, not by adding the element.
    await page.locator('#seek[data-movy-seek]').waitFor();
    await page.locator('#seek').focus();
    await page.keyboard.press('ArrowRight');
    assert.equal(await active(),'seek');
    assert.equal(await page.locator('video').evaluate(v=>v.currentTime),60);
    await page.keyboard.press('ArrowLeft');
    assert.equal(await page.locator('video').evaluate(v=>v.currentTime),50);
    assert.equal(await page.locator('#seek').getAttribute('aria-valuenow'),'50');
    await page.keyboard.press('Enter');assert.equal(await active(),'seek');
    await page.keyboard.press('ArrowUp');assert.notEqual(await active(),'seek','Up leaves the seek bar');
    console.log('PASS seek bar focus, Left/Right seeking, OK retention, Up navigation');
    await page.evaluate(()=>{
      // A new watch shell resets the per-show automatic quality preference.
      const old=document.querySelector('#vp-shell'),replacement=document.createElement('div');
      replacement.id='vp-shell';replacement.append(...old.childNodes);old.replaceWith(replacement);
      const options=document.querySelector('.player-surface');
      options.innerHTML='<button id="quality1080" aria-pressed="true">1080p</button><button id="quality2160">2160p</button><button id="quality720">720p</button>';
      options.querySelectorAll('button').forEach(b=>b.onclick=()=>{
        options.querySelectorAll('button').forEach(c=>c.setAttribute('aria-pressed',String(c===b)));
        window.selectedQuality=b.textContent;
      });
    });
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(()=>window.selectedQuality),'2160p','highest available resolution replaces initial 1080p');
    await page.locator('#quality720').evaluate(b=>b.click());
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(()=>window.selectedQuality),'720p','manual quality remains selected');
    console.log('PASS highest initial quality and manual override');
    await page.evaluate(()=>{
      const trigger=document.createElement('button');trigger.id='episodes';trigger.textContent='Episodes';
      document.querySelector('.header-top').appendChild(trigger);
      trigger.onclick=()=>{
        const drawer=document.createElement('div');drawer.id='drawer';drawer.className='z-[110]';
        drawer.style.cssText='position:absolute;inset:0;background:#222;z-index:110';
        drawer.innerHTML='<button aria-label="Close" style="position:absolute;right:20px;top:20px">Close</button><div id="episode1" class="cursor-pointer" style="position:absolute;left:200px;right:20px;top:150px;height:80px">Episode one</div><div id="episode2" class="cursor-pointer" style="position:absolute;left:200px;right:20px;top:280px;height:80px">Episode two</div>';
        drawer.querySelector('button').onclick=()=>drawer.remove();
        document.querySelector('.vp-container').appendChild(drawer);
      };
    });
    await page.locator('#episodes').focus();await page.keyboard.press('Enter');await page.waitForTimeout(230);
    await page.keyboard.press('ArrowDown');assert.equal(await active(),'episode1');
    await page.keyboard.press('ArrowDown');assert.equal(await active(),'episode2');
    assert.equal(await page.evaluate(()=>window.__movyTV.back()),true);await page.waitForTimeout(230);
    assert.equal(await page.locator('#drawer').count(),0);
    assert.equal(await active(),'episodes');
    console.log('PASS episode drawer focus, non-button episode cards, Back to trigger');
    await page.evaluate(()=>{
      document.body.innerHTML='<video title="Background Trailer" style="position:fixed;inset:0;width:100%;height:100%"></video><button id="details" style="position:relative">Details</button>';
    });
    await page.waitForTimeout(100);
    assert.equal(await page.evaluate(()=>window.__movyTV.media('toggle')), false, 'background trailers never take playback keys');
    assert.equal(await active(), 'details', 'leaving player returns focus to page UI');
    assert.deepEqual(errors, []);
    console.log('PASS player OK, menu navigation, Back, media commands, seek limits, keyup isolation');

    // A portrait phone viewport, the native playback bridge, and presses the
    // site's own control never acts on. The player is far shorter than a
    // portrait viewport, and a dropped click used to invert the requested
    // state and cost two further presses before playback actually paused.
    const mobile = await browser.newContext({ viewport: { width: 412, height: 915 } });
    await mobile.addInitScript(() => {
      window.bridgeMessages = [];
      window.movyTvBridge = { postMessage: m => window.bridgeMessages.push(m) };
    });
    await mobile.addInitScript({ path: path.resolve(__dirname, '../app/src/main/assets/tv_enhance.js') });
    await mobile.route('https://www.movy.sx/**', route => route.fulfill({contentType: 'text/html', body: `<!doctype html>
      <style>body{margin:0}#vp-shell{position:fixed;inset:0;background:black}
      .vp-container{position:absolute;top:180px;left:0;width:412px;height:232px}
      video{width:100%;height:100%;display:block}button{padding:12px}
      </style>
      <div id="vp-shell"><div class="vp-container"><video></video>
      <div class="header-top"></div>
      <div class="transport"><button id="ButtonPlay">Pause</button></div></div></div>`}));
    const phone = await mobile.newPage();
    const phoneErrors = []; phone.on('pageerror', e => phoneErrors.push(e.message));
    await phone.goto('https://www.movy.sx/');
    const lastSignal = () => phone.evaluate(() => JSON.parse(bridgeMessages[bridgeMessages.length-1]).active);
    const paused = () => phone.locator('video').evaluate(v => v.paused);
    await phone.evaluate(() => {
      let stopped = false;
      const v = document.querySelector('video');
      Object.defineProperties(v, {paused:{get:()=>stopped}, duration:{get:()=>100}});
      v.play=()=>{stopped=false;v.dispatchEvent(new Event('play'));return Promise.resolve()};
      v.pause=()=>{stopped=true;v.dispatchEvent(new Event('pause'))};
      window.playClicks = 0;
      // The site's control is mounted but its handler never reaches the video.
      document.querySelector('#ButtonPlay').onclick = () => { window.playClicks++; };
    });
    await phone.waitForTimeout(150);
    assert.equal(await lastSignal(), true, 'a 16:9 player in a portrait viewport is still the watch surface');
    await phone.keyboard.press('Enter');
    await phone.waitForTimeout(600);
    assert.ok(await phone.evaluate(() => playClicks) > 0, "the site's own control is still tried first");
    assert.equal(await paused(), true, 'one press pauses even when the site drops the click');
    await phone.keyboard.press('Enter');
    await phone.waitForTimeout(600);
    assert.equal(await paused(), false, 'the next press resumes instead of repeating the pause');
    await phone.evaluate(() => document.querySelector('#ButtonPlay').remove());
    await phone.waitForTimeout(150);
    await phone.keyboard.press('Enter');
    await phone.waitForTimeout(600);
    assert.equal(await paused(), true, 'one press pauses when the transport never mounts at all');
    await phone.keyboard.press('Enter');
    await phone.waitForTimeout(600);
    assert.equal(await paused(), false, 'and one press resumes again');
    const signals = await phone.evaluate(() => bridgeMessages.length);
    await phone.evaluate(() => { document.querySelector('#vp-shell').remove(); });
    await phone.waitForTimeout(200);
    assert.equal(await lastSignal(), false, 'leaving the player releases the landscape lock');
    assert.equal(signals, 1, 'playback is reported on change, not on every frame');
    assert.deepEqual(phoneErrors, []);
    console.log('PASS portrait player, playback bridge, single-press pause when the site drops it');
  } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1});
