(function () {
  if (window.__movyNativeResolver) return;
  window.__movyNativeResolver = true;
  try { sessionStorage.setItem('ads-enabled-session', 'false'); } catch (_) {}
  var originalParse = JSON.parse;
  var lastBundle = '';
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
    var metadata = {type: 'tracks', sources: safeTracks(data.sources, false), subtitles: safeTracks(data.subtitles, true)};
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
  var timer = setInterval(function () {
    var shell = document.querySelector('#vp-shell');
    if (!shell) return;
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
