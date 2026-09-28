// Security4Her live-tracking page. Reads ?k=<share_token> and polls the public track() RPC.
(function () {
  "use strict";
  var POLL_MS = 10000;
  var STALE_MS = 2 * 60 * 1000;
  var cfg = window.S4H_CONFIG || {};
  var token = new URLSearchParams(location.search).get("k");
  var el = function (id) { return document.getElementById(id); };
  var map = null, marker = null, circle = null, trail = null, lastData = null, timer = null;

  function setStatus(kind, title, subtitle) {
    el("status").className = "status " + kind;
    el("title").innerHTML = title;
    el("subtitle").textContent = subtitle;
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function ago(ms) {
    var s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + " s ago";
    if (s < 3600) return Math.round(s / 60) + " min ago";
    return Math.round(s / 3600) + " h ago";
  }

  function fetchTrack() {
    return fetch(cfg.supabaseUrl.replace(/\/$/, "") + "/rest/v1/rpc/track", {
      method: "POST",
      headers: { "content-type": "application/json", apikey: cfg.supabaseAnonKey },
      body: JSON.stringify({ p_token: token }),
      cache: "no-store",
    }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    });
  }

  function ensureMap(lat, lng) {
    if (map || !window.L) return;
    map = L.map("map", { zoomControl: true }).setView([lat, lng], 16);
    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      attribution: "&copy; OpenStreetMap contributors",
    }).addTo(map);
  }

  function render(data) {
    lastData = data;
    if (!data) {
      setStatus("", "Link not found", "This live-location link is invalid. Ask the sender for a new one.");
      el("live").classList.add("hidden");
      stop();
      return;
    }
    var name = esc(data.name || "She");
    if (data.status !== "active") {
      setStatus("ended", name + " has ended the alert", "Location sharing has stopped. Call her to check in.");
      el("live").classList.add("hidden");
      stop();
      return;
    }
    var locs = data.locations || [];
    if (!locs.length) {
      setStatus("active", "SOS from " + name + '<span class="pill">LIVE</span>',
        "Waiting for her first location fix… Keep this page open.");
      el("live").classList.add("hidden");
      return;
    }
    var latest = locs[0];
    setStatus("active", "SOS from " + name + '<span class="pill">LIVE</span>',
      "She may be in danger. Her latest location is below.");
    el("live").classList.remove("hidden");
    el("navigate").href = "https://www.google.com/maps/dir/?api=1&destination=" + latest.lat + "," + latest.lng;
    el("coords").textContent = latest.lat.toFixed(5) + ", " + latest.lng.toFixed(5) +
      (latest.acc ? " (±" + Math.round(latest.acc) + " m)" : "");
    updateAge();

    ensureMap(latest.lat, latest.lng);
    if (!map) return; // Leaflet failed to load: text + navigate link still work
    var ll = [latest.lat, latest.lng];
    if (!marker) {
      marker = L.marker(ll).addTo(map);
      circle = L.circle(ll, { radius: latest.acc || 0, color: "#C62828", weight: 1, fillOpacity: 0.12 }).addTo(map);
      trail = L.polyline([], { color: "#5B2A86", weight: 3, opacity: 0.7 }).addTo(map);
    } else {
      marker.setLatLng(ll);
      circle.setLatLng(ll).setRadius(latest.acc || 0);
      if (!map.getBounds().pad(-0.2).contains(ll)) map.panTo(ll);
    }
    trail.setLatLngs(locs.slice().reverse().map(function (p) { return [p.lat, p.lng]; }));
  }

  function updateAge() {
    var locs = lastData && lastData.locations;
    if (!locs || !locs.length) return;
    var age = Date.now() - new Date(locs[0].at).getTime();
    el("updated").innerHTML = "Updated " + ago(age) +
      (age > STALE_MS ? ' · <span class="warn">location may be out of date</span>' : "");
  }

  function poll() {
    fetchTrack().then(render).catch(function () {
      if (!lastData) setStatus("", "Can't load location", "Check your internet connection. Retrying…");
    });
  }

  function stop() {
    if (timer) clearInterval(timer);
    timer = null;
  }

  if (!token) {
    setStatus("", "Missing link code", "Open the full link from the SOS message.");
    return;
  }
  if (!cfg.supabaseUrl || cfg.supabaseUrl.indexOf("__") === 0) {
    setStatus("", "Page not configured", "Set SUPABASE_URL and SUPABASE_ANON_KEY for this site.");
    return;
  }
  poll();
  timer = setInterval(poll, POLL_MS);
  setInterval(updateAge, 1000);
})();
