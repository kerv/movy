(function () {
  if (window.__movyNativeResolver) return;
  window.__movyNativeResolver = true;
  try { sessionStorage.setItem('ads-enabled-session', 'false'); } catch (_) {}
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
