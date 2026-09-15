(function () {
  'use strict';
  if (window.__movyTvInit) return;
  window.__movyTvInit = true;
  var site = /(^|\.)movy\.sx$/.test(location.hostname);
  var embed = /(^|\.)vidy\.st$/.test(location.hostname);
  if (!site && !embed) return;

  // The site's own Ads switch, before React initializes its provider.
  if (site) {
    try { sessionStorage.setItem('ads-enabled-session', 'false'); } catch (_) {}
  }
  window.open = function () { return null; };

  var FOCUSABLE = 'a[href],button,input,select,textarea,[tabindex],[role="button"],[role="option"],[role="slider"],video,iframe';
  var hideTimer, pending = false, lastUrl = location.href;
  var lastPlayer = null, lastFocus = null, menuTrigger = null, focusRect = null;
  var handledKeys = new Set();
  var focusHistory = new Map();
  var focusFrame, focusLabel;
  var playbackFocusPending = false;
  var pauseRequested = false;
  var pendingPlaybackState = null;
  var verifyTimer = 0;
  var playbackSignal = null;
  var qualityShell = null, manualQuality = false, automaticQuality = false, highestQualityTried = 0;

  function controlsVisible() {
    var s = shell();
    return !!(s && s.hasAttribute('data-movy-controls'));
  }
  function focusPlayback() {
    playbackFocusPending = true;
    var button = document.getElementById('ButtonPlay');
    if (button && visible(button, true)) {
      focus(button);
      playbackFocusPending = false;
    } else {
      // The controls load lazily. Never substitute the first candidate: that
      // is the Back button. Retry when the actual play button is mounted.
      var s = shell(), active = document.activeElement;
      if (s && s.contains(active)) active.blur();
    }
  }

  // Paint outside the site's overflow:hidden card wrappers. A CSS outline on
  // the link alone is clipped by those wrappers, especially in poster rails.
  function paintFocus() {
    if (!document.body) return;
    if (!focusFrame || !focusFrame.isConnected) {
      focusFrame = document.createElement('div');
      focusFrame.id = '__movy_focus_frame';
      focusFrame.setAttribute('aria-hidden', 'true');
      focusLabel = document.createElement('span');
      focusFrame.appendChild(focusLabel);
      document.body.appendChild(focusFrame);
    }
    var active = document.activeElement, modal = popup();
    if (!active || active === document.body || !active.matches(FOCUSABLE) ||
        !visible(active, true) || (modal && !modal.contains(active))) {
      focusFrame.style.display = 'none';
      return;
    }
    // The overlay sits on top of the focused element. Let text fields use
    // their lighter native outline so their first typed character is never
    // covered by the frame's left edge.
    if (active.matches('input,textarea,select')) {
      focusFrame.style.display = 'none';
      return;
    }
    // A poster link represents the whole card. Once a card action (Play,
    // More, etc.) has focus, frame that action itself so it is clear which
    // button will run.
    var card = active.matches('a[href]') && active.closest('.media-card');
    var target = card || active;
    var r = target.getBoundingClientRect();
    // Keep the frame inside the viewport, including the first/last rail card.
    var left = Math.max(5, r.left), top = Math.max(5, r.top);
    var right = Math.min(innerWidth - 5, r.right), bottom = Math.min(innerHeight - 5, r.bottom);
    focusFrame.style.cssText = 'display:block;left:' + left + 'px;top:' + top +
      'px;width:' + Math.max(0, right - left) + 'px;height:' + Math.max(0, bottom - top) + 'px;';
    var heading = card && card.querySelector('h3,h2');
    var title = heading && heading.textContent.trim();
    var label = title ? '✓ ' + title : '';
    if (focusLabel.textContent !== label) focusLabel.textContent = label;
    focusLabel.style.display = label ? 'block' : 'none';
  }

  function attr(el, name, value) {
    if (el.getAttribute(name) !== value) el.setAttribute(name, value);
  }
  function trustedFrame(frame) {
    try {
      var url = new URL(frame.getAttribute('src') || '', location.href);
      return !!frame.getAttribute('src') && /(^|\.)(movy\.sx|vidy\.st)$/.test(url.hostname);
    } catch (_) { return false; }
  }
  function isProtected(el) {
    return el === document.body || el === document.documentElement || el.matches('video') ||
      !!el.querySelector('video') || Array.from(el.matches('iframe') ? [el] : el.querySelectorAll('iframe')).some(trustedFrame);
  }
  function cleanAds() {
    // Observed 2026-09-12: fl.fasolacaymans.com puts the QR ad in an
    // about:blank iframe. No guesses based on modal size, color or close X.
    document.querySelectorAll('iframe').forEach(function (frame) {
      if (isProtected(frame)) return;
      var host = '';
      try { host = new URL(frame.src).hostname; } catch (_) {}
      var knownAd = /(^|\.)(fasolacaymans\.com|doubleclick\.net|googlesyndication\.com)$/.test(host);
      var qrAd = false;
      try {
        var text = frame.contentDocument && frame.contentDocument.body && frame.contentDocument.body.innerText || '';
        qrAd = /confirm you.re not a robot/i.test(text) && /scan.*qr.code.*phone/i.test(text);
      } catch (_) {}
      if (knownAd || qrAd) frame.remove();
    });
    document.querySelectorAll('ins.adsbygoogle,[id^="aswift"],[aria-label="Advertisement"]').forEach(function (el) {
      if (!isProtected(el)) el.remove();
    });
  }
  function player() {
    // Details pages can contain large autoplaying preview videos too. Only
    // the actual watch surface should own playback keys or suppress page UI.
    return Array.from(document.querySelectorAll(site ? '#vp-shell video' : 'video')).find(function (v) {
      var r = v.getBoundingClientRect();
      // Portrait phones are much taller than a 16:9 video, so a share of the
      // viewport height alone would reject the real player. Fall back to the
      // element's own aspect once the viewport is taller than the video.
      return r.width > innerWidth * 0.4 && r.height > Math.min(innerHeight * 0.35, r.width * 0.4);
    }) || null;
  }
  function shell() { return document.querySelector('#vp-shell') || (embed ? document.body : null); }
  function qualityHeight(button) {
    if (!button || !button.matches('button') || !button.closest('#vp-shell .header-top .player-surface')) return 0;
    var match = button.textContent.trim().match(/^(\d{3,4})p|^([48])k/i);
    return match ? (match[1] ? Number(match[1]) : Number(match[2]) * 540) : 0;
  }
  document.addEventListener('click', function (e) {
    if (!automaticQuality && qualityHeight(e.target.closest('button'))) manualQuality = true;
  }, true);
  function preferBestQuality() {
    var s = shell();
    if (s !== qualityShell) {
      qualityShell = s; manualQuality = false; highestQualityTried = 0;
    }
    if (!s || manualQuality) return;
    var choices = Array.from(s.querySelectorAll('.header-top .player-surface button')).filter(qualityHeight);
    var current = choices.find(function (b) {
      return b.classList.contains('bg-white/[0.14]') || b.getAttribute('aria-pressed') === 'true';
    });
    if (!current) return;
    var best = choices.sort(function (a,b) { return qualityHeight(b) - qualityHeight(a); })[0];
    var height = qualityHeight(best);
    if (height <= highestQualityTried) return;
    highestQualityTried = height;
    if (height <= qualityHeight(current)) return;
    automaticQuality = true;
    try { best.click(); } finally { automaticQuality = false; }
  }
  function revealTransport() {
    var s = shell();
    if (!s) return;
    s.querySelectorAll('[data-movy-transport]').forEach(function (el) {
      el.removeAttribute('data-movy-transport');
    });
    // The site has changed this wrapper's Tailwind classes more than once.
    // Follow ButtonPlay upward and release every ancestor that actually hides
    // or moves it, rather than assuming the transport row is `.bottom-0`.
    var button = document.getElementById('ButtonPlay');
    for (var p = button && button.parentElement; p && p !== s; p = p.parentElement) {
      var cs = getComputedStyle(p);
      if (p.getAttribute('aria-hidden') === 'true' || Number(cs.opacity) < 0.99 ||
          cs.pointerEvents === 'none' || cs.transform !== 'none') {
        attr(p, 'data-movy-transport', 'true');
      }
    }
  }
  function forcedBar(el) {
    return controlsVisible() && (el.matches('#vp-shell .header-top, #vp-shell .bottom-0 > [aria-hidden]') ||
      el.hasAttribute('data-movy-transport'));
  }
  // Hidden menus leave their buttons laid out inside opacity:0 containers.
  function visible(el, onScreen) {
    if (!el || !el.isConnected || el.matches(':disabled,[aria-disabled="true"]')) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return false;
    for (var p = el; p; p = p.parentElement) {
      var cs = getComputedStyle(p);
      if (p.hidden || p.hasAttribute('inert') || cs.display === 'none' || cs.visibility === 'hidden') return false;
      if (!forcedBar(p) && (p.getAttribute('aria-hidden') === 'true' || Number(cs.opacity) < 0.05)) return false;
      // Offscreen targets are reachable through scrollable containers only.
      if (p !== el && p !== document.body && p !== document.documentElement) {
        var pr = p.getBoundingClientRect();
        if ((cs.overflowX === 'hidden' || cs.overflowX === 'clip') && (r.right <= pr.left || r.left >= pr.right)) return false;
        if ((cs.overflowY === 'hidden' || cs.overflowY === 'clip') && (r.bottom <= pr.top || r.top >= pr.bottom)) return false;
      }
    }
    return !onScreen || (r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth);
  }
  function popup() {
    return Array.from(document.querySelectorAll('.player-surface,[role="dialog"],[role="listbox"],[role="menu"],.glass-card-dark,#vp-shell [class~="z-[110]"]'))
      .filter(function (el) { return visible(el, true); }).pop() || null;
  }
  function candidates(scope) {
    return Array.from((scope || document).querySelectorAll(FOCUSABLE)).filter(function (el) {
      if (!visible(el, false) || el.getAttribute('tabindex') === '-1') return false;
      if (el.tagName === 'VIDEO') return false;
      if (el.tagName === 'IFRAME' && !trustedFrame(el)) return false;
      // The hero renders a button inside a link: one target per action.
      if (el.tagName === 'BUTTON' && el.closest('a[href]')) return false;
      return true;
    });
  }
  function focus(el) {
    if (!el) return false;
    if (!el.hasAttribute('tabindex') && !el.matches('a,button,input,select,textarea')) attr(el, 'tabindex', '0');
    el.focus({preventScroll:true});
    el.scrollIntoView({block:'nearest', inline:'nearest', behavior:'instant'});
    lastFocus = el;
    focusRect = el.getBoundingClientRect();
    paintFocus();
    return document.activeElement === el;
  }
  function pick(dir) {
    var scope = popup() || shell() || document;
    var list = candidates(scope), active = document.activeElement;
    if (!visible(active, true) || active === document.body || active.tagName === 'VIDEO' || !scope.contains(active)) {
      return focus(list.find(function (el) { return visible(el, true); }));
    }
    var r = active.getBoundingClientRect();
    var x = (r.left + r.right) / 2, y = (r.top + r.bottom) / 2;
    var horizontal = dir === 'left' || dir === 'right';
    // Search result cards contain a poster link followed by actions. Give
    // those actions priority when leaving the poster with Down. Geometric
    // selection alone can otherwise choose a card in the next result row.
    var card = active.matches('a[href]') && active.closest('.media-card');
    if (card && dir === 'down') {
      var cardAction = candidates(card).filter(function (el) {
        if (el === active || el.contains(active) || active.contains(el)) return false;
        var b = el.getBoundingClientRect();
        return b.top >= r.bottom - 4;
      })[0];
      if (cardAction) return focus(cardAction);
    }
    var best, score = Infinity;
    list.forEach(function (el) {
      if (el === active || el.contains(active) || active.contains(el)) return;
      var b = el.getBoundingClientRect();
      var dx = (b.left + b.right) / 2 - x, dy = (b.top + b.bottom) / 2 - y;
      var forward = dir === 'left' ? -dx : dir === 'right' ? dx : dir === 'up' ? -dy : dy;
      if (forward < 4) return;
      var cross = Math.abs(horizontal ? dy : dx);
      var overlap = horizontal ? Math.min(r.bottom, b.bottom) - Math.max(r.top, b.top) : Math.min(r.right, b.right) - Math.max(r.left, b.left);
      var value = forward + cross * 3 + (overlap > 0 ? 0 : 1000);
      if (value < score) { best = el; score = value; }
    });
    return best ? focus(best) : false;
  }
  function showControls() {
    var s = shell();
    if (!s) return;
    attr(s, 'data-movy-controls', 'true');
    revealTransport();
    // React listens on the video surface *inside* .vp-container. Events on
    // the outer container cannot reach that descendant. The mobile layout
    // unmounts transport buttons when idle, so CSS alone cannot restore them.
    var surface = player();
    if (surface) {
      var rect = surface.getBoundingClientRect();
      surface.dispatchEvent(new MouseEvent('mousemove', {bubbles:true,
        clientX:rect.left + rect.width / 2, clientY:rect.top + rect.height / 2}));
      surface.dispatchEvent(new Event('touchmove', {bubbles:true}));
    }
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function () {
      var v = player();
      if (v && (pauseRequested || v.paused || popup())) { showControls(); return; }
      hideControls();
    }, 5000);
  }
  function hideControls() {
    clearTimeout(hideTimer);
    playbackFocusPending = false;
    var s = shell();
    if (s) s.removeAttribute('data-movy-controls');
    if (s && s.contains(document.activeElement)) document.activeElement.blur();
  }
  function isPlayPauseTarget(el) {
    return !el || el === document.body || el.tagName === 'VIDEO' ||
      el.id === 'ButtonPlay' || !visible(el, true) || !el.matches(FOCUSABLE);
  }
  function playButton() { return document.getElementById('ButtonPlay'); }
  function directPlayback(v, pause) {
    if (pause) v.pause();
    else { var promise = v.play(); if (promise) promise.catch(function () {}); }
  }
  function clearVerify() { clearTimeout(verifyTimer); verifyTimer = 0; }
  function playbackBusy() { return verifyTimer !== 0 || pendingPlaybackState !== null; }
  function applyPlaybackState(v, pause) { applyPlayback(v, pause, 0); }
  /**
   * Movy's React state only follows its own control, so #ButtonPlay stays the
   * preferred route. Every request is then verified against the video: a
   * silently dropped one used to leave the requested state inverted, which
   * cost the viewer two further presses before playback actually paused.
   */
  function applyPlayback(v, pause, attempt) {
    clearVerify();
    if (v.paused === pause) { pendingPlaybackState = null; return; }
    var button = playButton();
    if (site && !button && attempt === 0) {
      // The mobile layout unmounts its transport while idle. showControls()
      // makes the site mount it again; apply as soon as that button lands.
      pendingPlaybackState = pause;
    } else if (button && attempt === 0) {
      pendingPlaybackState = null;
      button.click();
    } else {
      // Last resort: the site never took the request. An unsynchronized icon
      // is better than a remote press that does nothing.
      pendingPlaybackState = null;
      directPlayback(v, pause);
      return;
    }
    verifyTimer = setTimeout(function () {
      verifyTimer = 0;
      var current = player();
      if (current) applyPlayback(current, pause, attempt + 1);
      else pendingPlaybackState = null;
    }, 350);
  }
  function media(action) {
    var v = player();
    if (!v) return false;
    if (action === 'rewind' || action === 'forward') {
      var end = Number.isFinite(v.duration) ? v.duration : (v.seekable.length ? v.seekable.end(v.seekable.length - 1) : v.currentTime);
      var start = v.seekable.length ? v.seekable.start(0) : 0;
      v.currentTime = Math.max(start, Math.min(end, v.currentTime + (action === 'rewind' ? -10 : 10)));
    } else {
      // Derive the next state from the video itself, plus any request still in
      // flight. A latched flag can disagree with what is actually playing, and
      // then swallows the following press instead of pausing.
      var now = pendingPlaybackState !== null ? pendingPlaybackState : v.paused;
      pauseRequested = action === 'pause' ? true : action === 'play' ? false : !now;
      // Keep the site's React paused state and icon in sync with the video.
      applyPlaybackState(v, pauseRequested);
    }
    showControls();
    if (action === 'toggle' || action === 'pause' || action === 'play') {
      if (!popup()) focusPlayback();
    }
    return true;
  }
  function escapePopup() {
    var p = popup(), expanded = document.querySelector('[aria-expanded="true"]');
    if (!p && !expanded) return false;
    var trigger = menuTrigger;
    var close = p && p.querySelector('button[aria-label="Close"]');
    if (close && visible(close, true)) close.click();
    else document.dispatchEvent(new KeyboardEvent('keydown', {key:'Escape', keyCode:27, bubbles:true}));
    setTimeout(function () { if (visible(trigger, true)) focus(trigger); }, 180);
    return true;
  }
  function back() {
    if (escapePopup()) return true;
    if (player()) {
      if (controlsVisible() && !player().paused) { hideControls(); return true; }
      var b = document.querySelector('#vp-shell .header-top svg.cursor-pointer');
      if (b) { b.dispatchEvent(new MouseEvent('click', {bubbles:true})); return true; }
    }
    return false;
  }
  // Tell the app when the watch surface owns the screen, so phones can browse
  // in portrait and still play in landscape. Restricted origins only; see
  // MainActivity's WebMessageListener.
  function signalPlayback(active) {
    if (active === playbackSignal) return;
    playbackSignal = active;
    try {
      if (window.movyTvBridge) {
        window.movyTvBridge.postMessage(JSON.stringify({type:'playback', active:active}));
      }
    } catch (_) {}
  }
  window.__movyTV = {media:media, back:back};
  function editing(el) { return el && (el.isContentEditable || el.matches('input,textarea,select')); }
  function consume(e) { e.preventDefault(); e.stopImmediatePropagation(); }

  window.addEventListener('keydown', function (e) {
    var dir = {37:'left',38:'up',39:'right',40:'down'}[e.keyCode];
    var ok = e.keyCode === 13 || e.keyCode === 23 || e.keyCode === 32;
    var a = document.activeElement;
    if (!dir && !ok) return;
    // Text editing and native select/range widgets retain their default keys.
    if (editing(a)) {
      if (dir && (e.keyCode === 38 || e.keyCode === 40) && a.matches('input:not([type="range"]),textarea')) {
        if (pick(dir)) { handledKeys.add(e.keyCode); consume(e); }
      }
      return;
    }
    handledKeys.add(e.keyCode);
    consume(e);
    var v = player();
    if (v && a && a.hasAttribute('data-movy-seek') && !popup()) {
      if (dir === 'left' || dir === 'right') {
        media(dir === 'left' ? 'rewind' : 'forward');
        updateSeekBar(v);
        return;
      }
      if (ok) { showControls(); return; }
    }
    if (v) {
      var wasHidden = !controlsVisible() && !popup();
      showControls();
      if (wasHidden) {
        if (ok && !e.repeat) media('toggle');
        focusPlayback();
        return;
      }
      // Only the play button or an unfocused surface means play/pause.
      // Generic, unlabeled buttons still belong to the site's menu handlers.
      if (ok && !e.repeat && !popup() && isPlayPauseTarget(a)) {
        media('toggle');
        focusPlayback();
        return;
      }
    }
    if (dir) { pick(dir); return; }
    if (e.repeat) return;
    if (a && a.matches(FOCUSABLE) && visible(a, true)) {
      if (a.tagName === 'VIDEO') media('toggle');
      else {
        var before = popup();
        var qualityChoice = before && before.classList.contains('player-surface') && qualityHeight(a);
        if (!before || !before.contains(a)) menuTrigger = a;
        if (typeof a.click === 'function') a.click();
        else a.dispatchEvent(new MouseEvent('click', {bubbles:true}));
        setTimeout(function () {
          var p = popup();
          if (qualityChoice) {
            if (p === before) escapePopup();
            else if (visible(menuTrigger, true)) focus(menuTrigger);
          } else if (p && p !== before) focus(candidates(p)[0]);
        }, 180);
      }
    } else pick('down');
  }, true);
  // Site player shortcuts run on keyUP. Without this, focus moves also seek
  // and change volume. OK activation above must also happen only once.
  window.addEventListener('keyup', function (e) {
    if (handledKeys.delete(e.keyCode)) consume(e);
    else if (editing(document.activeElement)) e.stopImmediatePropagation();
  }, true);
  document.addEventListener('focusin', function (e) {
    lastFocus = e.target;
    focusRect = e.target.getBoundingClientRect();
    if (shell() && shell().contains(e.target)) showControls();
    if (e.target.matches(FOCUSABLE) && e.target.id !== 'ButtonPlay') playbackFocusPending = false;
    paintFocus();
  });
  document.addEventListener('focusout', function () { setTimeout(paintFocus, 0); });
  document.addEventListener('pause', function (e) {
    if (e.target !== player()) return;
    showControls();
    if (!popup() && isPlayPauseTarget(document.activeElement)) focusPlayback();
  }, true);
  document.addEventListener('play', function (e) {
    if (e.target !== player()) return;
    showControls();
    // Changing resolution/server reloads the source and the site autoplays
    // it. Preserve the viewer's pause until they explicitly choose Resume.
    if (pauseRequested) setTimeout(function () {
      if (pauseRequested && e.target === player()) applyPlaybackState(e.target, true);
    }, 0);
  }, true);

  function updateSeekBar(v) {
    // Movy's seek widget is a plain div with pointer handlers. Give that
    // existing surface slider semantics and remote focus, without replacing
    // its progress rendering or pointer behavior.
    var progress = document.querySelector('#vp-shell [class*="videoSeekBar"][class*="seekLeft"]');
    var seek = progress && progress.closest('.cursor-pointer');
    if (!seek) return;
    attr(seek, 'data-movy-seek', 'true');
    attr(seek, 'tabindex', '0');
    attr(seek, 'role', 'slider');
    attr(seek, 'aria-label', 'Seek — Left or Right skips 10 seconds');
    attr(seek, 'aria-valuemin', String(v.seekable.length ? v.seekable.start(0) : 0));
    attr(seek, 'aria-valuemax', String(Number.isFinite(v.duration) ? v.duration :
      (v.seekable.length ? v.seekable.end(v.seekable.length - 1) : v.currentTime)));
    attr(seek, 'aria-valuenow', String(Math.floor(v.currentTime)));
  }
  document.addEventListener('timeupdate', function (e) {
    if (e.target === player()) updateSeekBar(e.target);
  }, true);
  function maintain() {
    pending = false;
    cleanAds();
    signalPlayback(!!player());
    // No root transform writes in this observer: those caused a feedback loop
    // and shrank the document when a carousel overflowed its container.
    var v = player();
    if (location.href !== lastUrl) {
      if (lastFocus && lastFocus.getAttribute) {
        focusHistory.set(lastUrl, {href:lastFocus.getAttribute('href'), label:lastFocus.getAttribute('aria-label')});
      }
      lastUrl = location.href;
      lastFocus = null;
      focusRect = null;
      pauseRequested = false;
      pendingPlaybackState = null;
      clearVerify();
      manualQuality = false;
      highestQualityTried = 0;
      hideControls();
      var saved = focusHistory.get(lastUrl);
      if (saved) focus(candidates().find(function (el) {
        return saved.href ? el.getAttribute('href') === saved.href : saved.label && el.getAttribute('aria-label') === saved.label;
      }));
    }
    if (v !== lastPlayer) {
      var replacingPlayer = !!lastPlayer && !!v;
      lastPlayer = v;
      // A quality change can temporarily remove/resize the video while the
      // watch shell remains. That is not leaving playback: retain pause intent.
      if (!shell()) { hideControls(); pauseRequested = false; pendingPlaybackState = null; clearVerify(); }
      else if (v && !replacingPlayer && !pauseRequested) hideControls();
      // Enter playback once; never refocus it on a recurring timer.
      if (v && !replacingPlayer && !pauseRequested) {
        if (document.activeElement !== document.body) document.activeElement.blur();
      }
    }
    // Player controls are lazy-loaded after the video element.
    if (v) {
      preferBestQuality();
      updateSeekBar(v);
      if (controlsVisible()) revealTransport();
      // Only re-enter once the lazy control exists, or the mutation storm
      // around it would keep restarting the request and its verification.
      if (pendingPlaybackState !== null && (!site || playButton())) {
        applyPlaybackState(v, pendingPlaybackState);
      }
      if (pauseRequested && !v.paused && !playbackBusy()) applyPlaybackState(v, true);
      if (v.paused && !controlsVisible()) showControls();
      if (!popup() && (playbackFocusPending ||
          (controlsVisible() && lastFocus && lastFocus.id === 'ButtonPlay' && !lastFocus.isConnected))) {
        focusPlayback();
      }
      var b = document.querySelector('#vp-shell .header-top svg.cursor-pointer');
      if (b) { attr(b, 'tabindex', '0'); attr(b, 'role', 'button'); attr(b, 'aria-label', 'Back to details'); }
      document.querySelectorAll('#vp-shell [class~="z-[110]"] .cursor-pointer').forEach(function (el) {
        if (!el.matches('button,a,input') && !el.closest('button,a')) {
          attr(el, 'tabindex', '0'); attr(el, 'role', 'button');
        }
      });
    }
    if (!v && !shell() && (document.activeElement === document.body || !document.activeElement)) {
      var list = candidates().filter(function (el) { return visible(el, true); });
      // Virtualized rows replace focused cards when scrolling. Recover the
      // same link (or nearest card), rather than jumping to the site logo.
      var href = lastFocus && lastFocus.getAttribute('href');
      var matches = href ? list.filter(function (el) { return el.getAttribute('href') === href; }) : [];
      var pool = matches.length ? matches : list;
      if (focusRect) pool.sort(function (a, b) {
        function distance(el) {
          var r = el.getBoundingClientRect();
          return Math.abs(r.left - focusRect.left) + Math.abs(r.top - focusRect.top);
        }
        return distance(a) - distance(b);
      });
      focus(pool[0]);
    }
    paintFocus();
  }
  function schedule() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(maintain);
  }
  function init() {
    if (!document.documentElement) { setTimeout(init, 0); return; }
    var style = document.createElement('style');
    style.id = '__movy_tv_style';
    style.textContent = [
      ':focus{outline:2px solid rgba(229,9,20,.8)!important;outline-offset:2px!important;}',
      'input:focus,textarea:focus,select:focus{outline-color:rgba(255,255,255,.85)!important;box-shadow:0 0 0 1px rgba(229,9,20,.7)!important;}',
      '#__movy_focus_frame{position:fixed!important;z-index:2147483000!important;pointer-events:none!important;box-sizing:border-box!important;border:2px solid rgba(255,255,255,.82)!important;border-radius:8px!important;box-shadow:0 0 0 2px rgba(229,9,20,.7)!important;}',
      '#__movy_focus_frame span{position:absolute;left:4px;right:4px;bottom:4px;padding:5px 8px;border-radius:4px;background:rgba(8,11,16,.88);color:#fff;font:700 14px/1.3 sans-serif;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}',
      '.media-card:focus-within h3{color:#fff!important;font-weight:800!important;}',
      '.media-card a{scroll-margin-top:96px;scroll-margin-bottom:24px;scroll-margin-left:12px;scroll-margin-right:12px;}',
      'html,body{max-width:100%!important;overflow-x:hidden!important;}',
      '#vp-shell[data-movy-controls] .header-top,#vp-shell[data-movy-controls] .bottom-0 > [aria-hidden],#vp-shell[data-movy-controls] [data-movy-transport]{opacity:1!important;transform:none!important;pointer-events:auto!important;}',
      '#vp-shell[data-movy-controls] .header-top{background:linear-gradient(#000b,transparent);}',
      '#vp-shell button:focus{background-color:#ffffff30!important;}',
      'a:focus button{outline:2px solid rgba(229,9,20,.8)!important;}'
    ].join('');
    (document.head || document.documentElement).appendChild(style);
    var observer = new MutationObserver(schedule);
    observer.observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['class','hidden','aria-hidden','src']});
    window.addEventListener('resize', schedule);
    document.addEventListener('scroll', paintFocus, true);
    // Follow hover transitions and animated rails without modifying their layout.
    setInterval(paintFocus, 150);
    window.addEventListener('popstate', schedule);
    window.addEventListener('load', schedule);
    // Catch contents populated later inside same-origin blank frames.
    setInterval(cleanAds, 1500);
    schedule();
  }
  init();
})();
