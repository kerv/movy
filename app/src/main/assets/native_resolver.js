(function () {
  if (window.__movyNativeResolver) return;
  window.__movyNativeResolver = true;
  try { sessionStorage.setItem('ads-enabled-session', 'false'); } catch (_) {}
  var originalParse = JSON.parse;
  var lastBundle = '';
  window.__movyNativeServerInfo = function () {
    var servers = [], selected = '';
    var shell = document.querySelector('#vp-shell');
    if (shell) Array.prototype.forEach.call(shell.querySelectorAll('button'), function (button) {
      var flag = button.querySelector('img[src*="/flags/"]');
      var name = flag && (flag.getAttribute('alt') || '').trim();
      if (!name || name.length > 60 || servers.some(function (s) { return s.name === name; })) return;
      var country = (flag.getAttribute('src') || '').match(/\/flags\/([a-z]{2})\.svg/);
      servers.push({name: name, label: name + (country ? ' · ' + country[1].toUpperCase() : '')});
      if (button.getAttribute('aria-pressed') === 'true' || button.className.indexOf('bg-white/[0.14]') !== -1) selected = name;
    });
    return {servers: servers, server: window.__movyNativeServerChosen || selected};
  };
  function reportSources(data) {
    if (!data || !Array.isArray(data.sources)) return;
    function safeTracks(list, subtitles) {
      return (Array.isArray(list) ? list : []).slice(0, 100).filter(function (track) {
        return track && typeof track.url === 'string' && /^https:\/\//i.test(track.url);
      }).map(function (track) {
        return subtitles ? {url: track.url, lang: track.lang || '', label: track.language || track.label || track.lang || ''}
          : {url: track.url, label: String(track.quality || track.label || '')};
      });
    }
    var info = window.__movyNativeServerInfo();
    if (window.__movyNativeRequestedServer === info.server) window.__movyNativeServerChosen = info.server;
    var metadata = {type: 'tracks', sources: safeTracks(data.sources, false), subtitles: safeTracks(data.subtitles, true), servers: info.servers, server: info.server};
    var bundle = JSON.stringify(metadata);
    // Keep a pullable copy even if this WebView lacks the message bridge or
    // the bridge was not ready when the source response was parsed.
    window.__movyNativeTracks = metadata;
    var cached = window.__movyNativeBundles || (window.__movyNativeBundles = []);
    if (!cached.length || JSON.stringify(cached[0]) !== bundle) cached.unshift(metadata);
    cached.splice(6);
    if (bundle !== lastBundle && window.movyResolverBridge) {
      lastBundle = bundle;
      window.movyResolverBridge.postMessage(bundle);
    }
  }
  // Movy's browser decrypts the source response before parsing it. Preserve
  // parsing behavior and observe only its advertised media/subtitle URLs.
  JSON.parse = function () {
    var data = originalParse.apply(this, arguments);
    try { reportSources(data); } catch (_) {}
    return data;
  };
  var originalJson = Response.prototype.json;
  Response.prototype.json = function () {
    return originalJson.apply(this, arguments).then(function (data) { reportSources(data); return data; });
  };
  var chosen = 0;
  var ticks = 0;
  var timer = setInterval(function () {
    ticks++;
    var shell = document.querySelector('#vp-shell');
    if (!shell) return;
    var requested = window.__movyNativeRequestedServer;
    if (requested && window.__movyNativeServerChosen !== requested) {
      // Allow the default response to settle first, so it cannot be mistaken
      // for the requested server's release when its request completes late.
      if (!window.__movyNativeTracks && ticks < 20) return;
      var serverButtons = shell.querySelectorAll('button');
      for (var i = 0; i < serverButtons.length; i++) {
        var flag = serverButtons[i].querySelector('img[src*="/flags/"]');
        if (flag && flag.getAttribute('alt') === requested) {
          window.__movyNativeServerChosen = requested;
          if (serverButtons[i].getAttribute('aria-pressed') === 'true' || serverButtons[i].className.indexOf('bg-white/[0.14]') !== -1) { window.__movyNativeDefaultRequested = true; return; }
          window.__movyNativeTracks = null;
          window.__movyNativeBundles = [];
          chosen = 0; lastBundle = '';
          serverButtons[i].click();
          return;
        }
      }
      return; // Never silently use the default when the requested server is absent.
    }
    var options = Array.from(shell.querySelectorAll('button, [role="menuitemradio"], [role="option"]'));
    var best = null, height = chosen;
    options.forEach(function (button) {
      var match = (button.textContent || '').trim().match(/^(\d{3,4})p(?:\s|$|HD|Full)/i);
      if (match && +match[1] > height) { best = button; height = +match[1]; }
    });
    if (best) { chosen = height; best.click(); }
    // Never click a catalogue/trailer Play button. ?play=true opens the
    // real watch shell after React hydration; the browser handles resolution.
  }, 400);
  setTimeout(function () { clearInterval(timer); }, 45000);
})();
