(function () {
  'use strict';
  var base = window.__movyAccountBase || 'https://backend.movy.sx/v1';
  function session() {
    var token = localStorage.getItem('JWT_TOKEN') || '';
    if (!token) return null;
    try {
      var part = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      var payload = JSON.parse(decodeURIComponent(Array.prototype.map.call(atob(part), function (c) {
        return '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2);
      }).join('')));
      var saved = JSON.parse(localStorage.getItem('app-active-profile') || 'null');
      return {token: token, username: payload.sub, profile: saved && saved.username === payload.sub ? saved.profile : null};
    } catch (_) { return null; }
  }
  async function api(path, method, body, state, profile) {
    var headers = {'Content-Type': 'application/json'};
    if (state) headers.Authorization = 'Bearer ' + state.token;
    if (profile && state.profile) headers['X-Profile-Id'] = state.profile.id;
    var abort = new AbortController(), timeout = setTimeout(function () { abort.abort(); }, 14000);
    try {
      var response = await fetch(base + path, {method: method || 'GET', headers: headers,
        body: body ? JSON.stringify(body) : undefined, signal: abort.signal});
      if (!response.ok) {
        var error = new Error(response.status === 429 ? 'Too many attempts. Please wait and try again.' :
          response.status === 401 || response.status === 403 ? 'Sign-in or profile verification failed.' : 'Movy could not complete this request.');
        error.status = response.status; throw error;
      }
      return response.status === 204 ? {} : await response.json();
    } finally { clearTimeout(timeout); }
  }
  async function snapshot() {
    var state = session();
    if (!state || !state.username) return {signedIn: false, rows: [], profiles: []};
    var userPath = '/user/' + encodeURIComponent(state.username);
    var user = await api(userPath, 'GET', null, state, false);
    var profiles = await api(userPath + '/profiles', 'GET', null, state, false);
    profiles = Array.isArray(profiles) ? profiles : profiles.profiles || [];
    var active = state.profile && profiles.find(function (p) { return String(p.id) === String(state.profile.id); });
    var result = {signedIn: true, username: user.username || state.username, email: user.email || '',
      avatar: user.avatar || '', profile: active || null, profiles: profiles, rows: [], completedSeries: [], recentHistory: [], partial: false};
    if (!active) return result;
    state.profile = active;
    var path = '/account/' + encodeURIComponent(state.username);
    var responses = await Promise.allSettled([api(path + '/history', 'GET', null, state, true), api(path + '/lists', 'GET', null, state, true)]);
    function mediaType(t) { var explicit=String(t.mediaType || t.media_type || '').toLowerCase();return explicit==='tv'||/\/tv\/\d+/.test(t.slug||t.url||'')?'tv':'movie'; }
    function title(t, history) {
      var type = mediaType(t);
      var slug = t.slug || t.url || '/' + type + '/' + t.id;
      try { slug = new URL(slug, 'https://www.movy.sx').pathname; } catch (_) { return null; }
      if (!/^\/(movie|tv)\/\d+(?:\/\d+\/\d+)?$/.test(slug)) return null;
      if (history && type === 'tv' && t.season && t.currentEpisode) slug = '/tv/' + t.id + '/' + t.season + '/' + t.currentEpisode;
      return {id: t.id, title: t.title || t.name || 'Untitled', slug: slug, poster: t.poster || '',
        image: t.image || t.background || '', release_date: t.release_date || '', rating: t.rating || 0,
        durationMs: history ? Math.max(0, Math.round((t.totalDuration || 0) * 1000)) : 0,
        resumeMs: history ? Math.max(0, Math.round((t.currentTime || 0) * 1000)) : 0};
    }
    if (responses[0].status === 'fulfilled') {
      var allHistory = responses[0].value.history || [];
      function completed(t) { return t.totalDuration > 0 && t.currentTime >= Math.max(t.totalDuration * .95, t.totalDuration - 60); }
      var history = allHistory.filter(function (t) { return t.currentTime > 0 && !completed(t); }).slice(0,40).map(function (t) { return title(t, true); }).filter(Boolean);
      result.completedSeries = allHistory.filter(function(t) { return mediaType(t) === 'tv' && completed(t); }).slice(0,40).map(function(t) { var item=title(t,true);if(item)item.resumeMs=0;return item; }).filter(Boolean);
      result.recentHistory = allHistory.filter(function(t) { return t.currentTime > 0; }).slice(0,40).map(function(t) { return title(t,false); }).filter(Boolean);
      if (history.length) result.rows.push({name: 'Continue Watching · ' + active.name, titles: history});
    } else result.partial = true;
    if (responses[1].status === 'fulfilled') {
      var lists = Array.isArray(responses[1].value) ? responses[1].value : responses[1].value.lists || [];
      lists.forEach(function (list) { var items = (list.items || []).map(function (t) { return title(t, false); }).filter(Boolean);
        if (items.length) result.rows.push({name: list.name || 'Movy Watchlist', titles: items}); });
    } else result.partial = true;
    return result;
  }
  window.__movyAccountRequest = async function (request) {
    try {
      var args = request.args || {}, state = session();
      if (request.action === 'login') {
        var device = localStorage.getItem('app-device-id');
        if (!device) { device = (window.__movyNativeDevicePrefix || 'movy-tv-') + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2); localStorage.setItem('app-device-id', device); }
        var login = await api('/auth/login', 'POST', {username: args.username, password: args.password,
          deviceName: window.__movyNativeDeviceName || 'Movy Android TV', deviceId: device}, null, false);
        if (!login.token) throw new Error('Movy did not return a sign-in session.');
        localStorage.setItem('JWT_TOKEN', login.token); localStorage.removeItem('app-active-profile');
      } else if (request.action === 'logout') {
        try { if (state) await api('/auth/logout', 'POST', {}, state, false); } catch (_) {}
        localStorage.removeItem('JWT_TOKEN'); localStorage.removeItem('app-active-profile');
      } else if (request.action === 'profile') {
        if (!state) throw new Error('Please sign in first.');
        var profiles = await api('/user/' + encodeURIComponent(state.username) + '/profiles', 'GET', null, state, false);
        profiles = Array.isArray(profiles) ? profiles : profiles.profiles || [];
        var profile = profiles.find(function (p) { return String(p.id) === String(args.id); });
        if (!profile) throw new Error('This profile is no longer available.');
        var pinStateKnown = typeof profile.hasPin === 'boolean' || typeof profile.pinEnabled === 'boolean' || typeof profile.pinRequired === 'boolean';
        // If the backend changes its PIN flag, verify rather than treating an
        // unknown profile shape as unlocked.
        if (!pinStateKnown || profile.hasPin || profile.pinEnabled || profile.pinRequired || args.pin) {
          var verified = await api('/user/' + encodeURIComponent(state.username) + '/profiles/' + encodeURIComponent(profile.id) + '/verify-pin', 'POST', {pin: args.pin || ''}, state, false);
          if (!verified || verified.valid === false || verified.success === false || verified.verified === false) throw new Error('Incorrect profile PIN.');
        }
        localStorage.setItem('app-active-profile', JSON.stringify({username: state.username, profile: profile}));
      } else if (request.action !== 'read') throw new Error('Unsupported account action.');
      window.movyAccountBridge.postMessage(JSON.stringify({id: request.id, ok: true, snapshot: await snapshot()}));
    } catch (error) {
      window.movyAccountBridge.postMessage(JSON.stringify({id: request.id, ok: false,
        error: error.status === 401 || error.status === 403 ? 'Sign-in or profile verification failed.' : error.message || 'Account connection failed.'}));
    }
  };
})();
