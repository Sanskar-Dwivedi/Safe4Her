/* The legacy Android wire format and the timestamped iPhone format share this parser. */
(function (root) {
  function parseLiveMessage(message, now) {
    now = now === undefined ? Date.now() : now;
    if (!message || message.event !== 'message') return null;
    var data;
    try { data = JSON.parse(message.message); } catch (_) {}
    var ended = !!(data && data.version === 1 && data.state === 'ended') ||
      (message.tags || []).indexOf('white_check_mark') >= 0;
    if (ended) return { ended: true };
    var lat, lng, at, accuracy;
    if (data && data.version === 1) {
      if (data.state !== 'active') return null;
      lat = data.lat; lng = data.lng; at = Date.parse(data.fixAt); accuracy = data.accuracyM;
    } else {
      var link;
      try { link = new URL(message.click); } catch (_) { return null; }
      var coordinates = link.searchParams.get('q') || link.searchParams.get('ll') || '';
      var match = /^(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)$/.exec(coordinates);
      if (!match) return null;
      lat = Number(match[1]); lng = Number(match[2]); at = message.time * 1000;
      var acc = /accurate to (\d+(?:\.\d+)?) m/.exec(message.message || '');
      accuracy = acc ? Number(acc[1]) : null;
    }
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180 ||
        !Number.isFinite(at) || at <= 0 || at > now + 5000 ||
        (accuracy != null && (!Number.isFinite(accuracy) || accuracy < 0))) return null;
    return { ended: false, point: [lat, lng], at: at, accuracy: accuracy };
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = parseLiveMessage;
  else root.parseLiveMessage = parseLiveMessage;
})(typeof globalThis !== 'undefined' ? globalThis : this);
