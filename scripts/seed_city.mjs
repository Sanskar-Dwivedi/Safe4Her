#!/usr/bin/env node
// Builds a SQL seed of safe places (police, hospitals, clinics, pharmacies, fire stations) for a city
// from OpenStreetMap, plus optional curated danger hotspots ("known information").
//
//   node scripts/seed_city.mjs "Pune, India"                   # geocode + 8 km radius
//   node scripts/seed_city.mjs "Pune, India" --radius-km 5
//   node scripts/seed_city.mjs --bbox 18.45,73.75,18.60,73.95  # south,west,north,east
//   node scripts/seed_city.mjs --overpass-json scripts/fixtures/overpass_sample.json   # offline
//   ... --hotspots supabase/seed/known_hotspots.csv --out supabase/seed/pune.sql
//
// Then run the SQL in the Supabase SQL editor (or psql). Re-running updates rows by osm_id.
// OSM data © OpenStreetMap contributors, ODbL. Keep attribution in the app's map.
import fs from "node:fs";
import path from "node:path";

const UA = "Security4Her-hackathon-seed/0.1 (contact: project maintainers)";
const CATEGORIES = ["police", "hospital", "clinic", "pharmacy", "fire_station"];
const OFFICIAL = new Set(["police", "hospital", "fire_station"]); // public services → shown as verified

function parseArgs(argv) {
  const opts = { radiusKm: 8, out: null, bbox: null, overpassJson: null, hotspots: null, query: null };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    if (a === "--radius-km") opts.radiusKm = Number(next());
    else if (a === "--out") opts.out = next();
    else if (a === "--bbox") opts.bbox = next().split(",").map(Number);
    else if (a === "--overpass-json") opts.overpassJson = next();
    else if (a === "--hotspots") opts.hotspots = next();
    else if (a === "--help" || a === "-h") { console.log(fs.readFileSync(new URL(import.meta.url)).toString().split("\n").slice(1, 13).join("\n")); process.exit(0); }
    else if (!a.startsWith("--")) opts.query = a;
    else throw new Error(`unknown option ${a}`);
  }
  if (!opts.query && !opts.bbox && !opts.overpassJson) throw new Error("give a city name, --bbox or --overpass-json");
  if (opts.bbox && (opts.bbox.length !== 4 || opts.bbox.some(Number.isNaN))) throw new Error("--bbox needs south,west,north,east");
  return opts;
}

export function bboxAround(lat, lng, radiusKm) {
  const dLat = radiusKm / 111.32;
  const dLng = radiusKm / (111.32 * Math.cos((lat * Math.PI) / 180));
  return [lat - dLat, lng - dLng, lat + dLat, lng + dLng].map((v) => Number(v.toFixed(5)));
}

async function geocode(query) {
  const url = `https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encodeURIComponent(query)}`;
  const res = await fetch(url, { headers: { "user-agent": UA } });
  if (!res.ok) throw new Error(`nominatim HTTP ${res.status}`);
  const [hit] = await res.json();
  if (!hit) throw new Error(`place not found: ${query}`);
  return { lat: Number(hit.lat), lng: Number(hit.lon), name: hit.display_name };
}

async function overpass(bbox) {
  const [s, w, n, e] = bbox;
  const q = `[out:json][timeout:90];
nwr["amenity"~"^(${CATEGORIES.join("|")})$"](${s},${w},${n},${e});
out center tags;`;
  for (const endpoint of ["https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"]) {
    try {
      const res = await fetch(endpoint, {
        method: "POST",
        headers: { "user-agent": UA, "content-type": "application/x-www-form-urlencoded" },
        body: "data=" + encodeURIComponent(q),
      });
      if (res.ok) return await res.json();
      console.error(`overpass ${endpoint}: HTTP ${res.status}`);
    } catch (e) {
      console.error(`overpass ${endpoint}: ${e.message}`);
    }
  }
  throw new Error("all Overpass endpoints failed");
}

const sql = (v) => (v == null || v === "" ? "null" : `'${String(v).replace(/'/g, "''")}'`);

export function placesFromOverpass(data) {
  const seen = new Set();
  const out = [];
  for (const el of data.elements ?? []) {
    const t = el.tags ?? {};
    const category = t.amenity;
    if (!CATEGORIES.includes(category)) continue;
    const lat = el.lat ?? el.center?.lat;
    const lng = el.lon ?? el.center?.lon;
    if (typeof lat !== "number" || typeof lng !== "number") continue;
    const osmId = `${el.type}/${el.id}`;
    if (seen.has(osmId)) continue;
    seen.add(osmId);
    const name = t.name || t["name:en"] || category.replace("_", " ").replace(/^\w/, (c) => c.toUpperCase());
    const address = [t["addr:housenumber"], t["addr:street"], t["addr:suburb"] || t["addr:city"]].filter(Boolean).join(", ");
    out.push({ name, category, lat, lng, osmId, phone: t.phone || t["contact:phone"] || null, address: address || null,
      verified: OFFICIAL.has(category) });
  }
  return out;
}

export function placesSql(places) {
  if (!places.length) return "-- no places found\n";
  const rows = places.map((p) =>
    `  (${sql(p.name)}, ${sql(p.category)}, st_setsrid(st_makepoint(${p.lng}, ${p.lat}), 4326)::geography, ` +
    `${p.verified}, 'osm', ${sql(p.osmId)}, ${sql(p.phone)}, ${sql(p.address)})`);
  const chunks = [];
  for (let i = 0; i < rows.length; i += 500) {
    chunks.push(`insert into public.safe_places (name, category, geog, verified, source, osm_id, phone, address) values\n` +
      rows.slice(i, i + 500).join(",\n") +
      `\non conflict (osm_id) do update set name = excluded.name, category = excluded.category, geog = excluded.geog,\n` +
      `  phone = excluded.phone, address = excluded.address;\n`);
  }
  return chunks.join("\n");
}

export function hotspotsSql(csvText) {
  const lines = csvText.split(/\r?\n/).map((l) => l.trim()).filter((l) => l && !l.startsWith("#"));
  const [header, ...rows] = lines;
  const cols = header.split(",").map((c) => c.trim());
  const idx = (c) => cols.indexOf(c);
  if (idx("lat") < 0 || idx("lng") < 0 || idx("source") < 0) throw new Error("hotspots CSV needs lat,lng,source[,note,weight]");
  const values = rows.map((r) => {
    const f = r.split(",").map((x) => x.trim());
    const lat = Number(f[idx("lat")]), lng = Number(f[idx("lng")]);
    if (Number.isNaN(lat) || Number.isNaN(lng)) throw new Error(`bad row: ${r}`);
    const weight = idx("weight") >= 0 && f[idx("weight")] ? Number(f[idx("weight")]) : 2;
    return `  (st_setsrid(st_makepoint(${lng}, ${lat}), 4326)::geography, ${sql(f[idx("source")])}, ` +
      `${sql(idx("note") >= 0 ? f[idx("note")] : null)}, ${weight})`;
  });
  if (!values.length) return "-- no hotspots\n";
  return `insert into public.known_hotspots (geog, source, note, weight) values\n${values.join(",\n")};\n` +
    `select public.refresh_danger_cells();\n`;
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  let data;
  let label;
  if (opts.overpassJson) {
    data = JSON.parse(fs.readFileSync(opts.overpassJson, "utf8"));
    label = path.basename(opts.overpassJson, ".json");
  } else {
    let bbox = opts.bbox;
    label = opts.query ?? bbox.join("_");
    if (!bbox) {
      const g = await geocode(opts.query);
      console.error(`geocoded: ${g.name} (${g.lat}, ${g.lng})`);
      bbox = bboxAround(g.lat, g.lng, opts.radiusKm);
    }
    console.error(`bbox s,w,n,e = ${bbox.join(",")}`);
    data = await overpass(bbox);
  }
  const places = placesFromOverpass(data);
  const counts = Object.fromEntries(CATEGORIES.map((c) => [c, places.filter((p) => p.category === c).length]));
  console.error(`places: ${places.length}`, counts);

  let out = `-- Security4Her seed: ${label}\n-- Generated ${new Date().toISOString()} from OpenStreetMap (© OSM contributors, ODbL)\n` +
    `set search_path = public, extensions;\n\n` + placesSql(places);
  if (opts.hotspots) out += "\n" + hotspotsSql(fs.readFileSync(opts.hotspots, "utf8"));

  const slug = label.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "") || "city";
  const file = opts.out ?? `supabase/seed/${slug}.sql`;
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, out);
  console.error(`wrote ${file}`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((e) => {
    console.error(`error: ${e.message}`);
    process.exit(1);
  });
}
