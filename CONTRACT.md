# Security4Her — Integration Contract (frozen at H0)

The phone app, watch app, backend and live page all code against this file. Change it first, then the
code, and note the change under "Change requests" at the bottom.

## 1. App identity & build

| Item | Value |
|---|---|
| `applicationId` / `namespace` | `in.security4her.app` for **both** `:wear` and `:mobile`. Required by the Wear Data Layer. |
| Debug signing | Both modules: `signingConfigs.getByName("debug") { storeFile = rootProject.file("keystore/debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" }` |
| Debug SHA‑1 (for the Maps key) | `65:C4:9B:AA:AB:62:FC:17:2F:D2:D4:1F:10:87:21:C6:DA:8D:15:11` |
| Plugins (root, `apply false`) | AGP `8.13.2`, `org.jetbrains.kotlin.android` / `plugin.compose` / `plugin.serialization` `2.3.21` |
| Gradle | wrapper 8.14.3 |
| SDK | compileSdk 36, targetSdk 35; minSdk mobile 26, wear 30; Java/JVM 17 |
| Repos | Declared in `settings.gradle.kts` only (FAIL_ON_PROJECT_REPOS). Do not add repos in module files. |
| Version catalog | **None.** Each module declares its own dependency coordinates inline. |

Config comes from the gitignored `local.properties` (see `local.properties.example`) and becomes
`BuildConfig` string fields in both modules: `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `TRACK_BASE_URL`.
Mobile also puts `MAPS_API_KEY` into the manifest as `com.google.android.geo.API_KEY` via
`manifestPlaceholders`. Reading snippet (copy into each module's `build.gradle.kts`):

```kotlin
val localProps = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cfg(key: String) = "\"${localProps.getProperty(key, "")}\""
// in defaultConfig { buildConfigField("String", "SUPABASE_URL", cfg("SUPABASE_URL")) ... }
// and buildFeatures { buildConfig = true; compose = true }
```

Shared library versions (use these so both modules resolve the same thing):

| Library | Version |
|---|---|
| Compose BOM `androidx.compose:compose-bom` | `2026.05.01` |
| `androidx.activity:activity-compose` | `1.13.0` |
| `androidx.core:core-ktx` | `1.16.0` |
| `androidx.lifecycle:lifecycle-*` | `2.10.0` |
| `androidx.datastore:datastore-preferences` | `1.1.1` |
| `androidx.work:work-runtime-ktx` | `2.10.1` |
| `com.google.android.gms:play-services-location` | `21.3.0` |
| `com.google.android.gms:play-services-wearable` | `19.0.0` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-play-services` | `1.10.2` |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.11.0` (same as supabase-kt 3.6.0) |
| `com.squareup.okhttp3:okhttp` | `4.12.0` |
| `io.github.jan-tennert.supabase:bom` (mobile) | `3.6.0` (last release built with Kotlin 2.3.21; 3.7+ needs Kotlin 2.4) |
| `io.ktor:ktor-client-okhttp` (mobile, supabase-kt engine) | `3.4.3` (same as supabase-kt 3.6.0) |
| `com.google.maps.android:maps-compose` (mobile) | `6.12.2` (8.x needs Kotlin 2.4) |
| `com.google.android.gms:play-services-maps` (mobile) | transitive from maps-compose, don't pin |
| Wear only: `androidx.wear.compose:compose-material3` / `compose-foundation` | `1.6.2` |
| constraint `androidx.fragment:fragment` | `1.8.9` (fixes the Activity Result lint error seen earlier) |

If Android Studio reports a newer compatible version, upgrading is fine. Change the row here in the
same commit.

## 2. Wear Data Layer

Capabilities are declared in `res/values/wear.xml` (`<string-array name="android_wear_capabilities">`):
the phone declares **`s4h_phone`** and the watch declares **`s4h_watch`**.

All payloads are UTF‑8 JSON. Unknown fields must be ignored (`Json { ignoreUnknownKeys = true }`).
Times are epoch milliseconds. Lat/lng are doubles and accuracy is a float in metres.

| Direction | Kind | Path | Payload |
|---|---|---|---|
| watch → phone | Message | `/s4h/sos/start` | `{"incidentId":"uuid","trigger":"knock"\|"shake"\|"button"\|"voice","createdAt":1727,"lat":28.6,"lng":77.2,"accuracyM":12.5,"fixAt":1727}` (lat/lng/accuracyM/fixAt optional) |
| watch → phone | Message | `/s4h/sos/location` | `{"incidentId","lat","lng","accuracyM","fixAt"}` |
| watch → phone | Message | `/s4h/sos/cancel` | `{"incidentId"}`, sent when the user cancels or says "I'm safe" on the watch; the phone ends the incident as `resolved` |
| watch → phone | Message | `/s4h/link/request` | `{}`: asks the phone to (re)write `/s4h/device` |
| phone → watch | Message | `/s4h/sos/ack` | `{"incidentId"}`, sent **immediately** on receiving start, before any SMS/network work |
| phone → watch | DataItem | `/s4h/status` | key `json` → `{"incidentId","state":"active"\|"cancelled"\|"resolved","smsSent":2,"smsFailed":0,"serverAccepted":true,"callPlaced":false,"shareUrl":"https://…","updatedAt":1727}` |
| phone → watch | DataItem | `/s4h/device` | key `json` → `{"userId","displayName","deviceToken","testMode":true}`, written after login + `register_watch()` and whenever Test Mode changes |

DataItems must be put with `setUrgent()`. The phone also writes `/s4h/status` for SOS started on the phone.

Rules:
- `incidentId` is a random UUID created where SOS is triggered, and every path dedupes on it.
- The watch never receives the user's Supabase session. It authenticates only with `deviceToken`.

## 3. Supabase backend

Migrations are in `supabase/migrations/`. After the H4 checkpoint everything below is live on the
cloud project. All RPCs are called via PostgREST `rpc/<name>` with named parameters exactly as written.

**Auth:** email + password (turn **off** "Confirm email" in Auth settings). Sign-up metadata
`{"display_name": "..."}` goes into `profiles.display_name`. Every app table and RPC requires
`profiles.verified_female = true`; calls from an unverified user raise `not verified`.

### Tables the phone uses directly
- `profiles` (select own; update only `display_name`, `phone`):
  `id uuid, display_name text, phone text, verified_female bool, verification_method text ('digilocker'|'mock'), verified_at timestamptz`
- `trusted_contacts` (full CRUD on own rows; `owner_id` defaults to `auth.uid()`):
  `id uuid, owner_id uuid, name text, phone text, relation text, priority int (1 = first), receives_sos bool, telegram_chat_id bigint (read-only for app), telegram_link_code text (read-only; show "send /start <code> to @<bot>")`
- `incidents` (select own): `id, source, trigger, status, test_mode, share_token, created_at, ended_at, expires_at`

### RPCs
| RPC | Caller | Returns | Notes |
|---|---|---|---|
| `mock_verify_female()` | phone (authenticated) | `boolean` | Works only while `app_config.digilocker_mode = 'mock'` |
| `upsert_incident(p_id uuid, p_trigger text, p_source text, p_lat float8 = null, p_lng float8 = null, p_acc real = null, p_test boolean = true)` | phone | one row `{id, share_token, status, created}` | Idempotent. `p_trigger` is knock/shake/button/voice/pocket/journey; `p_source` is `phone` or `watch` (i.e. relayed by the phone) |
| `add_incident_location(p_id uuid, p_lat float8, p_lng float8, p_acc real = null, p_fix_at timestamptz = now())` | phone | void | Ignored unless the incident is active |
| `end_incident(p_id uuid, p_status text)` | phone | void | `p_status` is `cancelled` or `resolved`; the share link stops working |
| `register_watch()` | phone | `text` device token | Replaces any previous watch token for the user |
| `track(p_token text)` | anon (web page) | `jsonb` or null | `{"name","status":"active"\|"ended","startedAt","locations":[{"lat","lng","acc","at"}...]}`; `locations` is empty once ended/expired |
| `nearest_safe_places(p_lat float8, p_lng float8, p_n int = 5)` | map | rows `{id, name, category, lat, lng, distance_m, phone, verified}` | Nearest first |
| `danger_cells_in_bbox(p_min_lat, p_min_lng, p_max_lat, p_max_lng float8)` | map | rows `{lat, lng, score, reports}` | ~300 m cells, only with ≥ 3 distinct reporters or official data |
| `report_unsafe(p_lat float8, p_lng float8, p_category text, p_note text = null)` | map | `bigint` id | Categories: poor_lighting, harassment, stalking, isolated, other. Max 10/day/user |

### Edge Functions (`https://<project>.supabase.co/functions/v1/...`)
| Function | Caller | Contract |
|---|---|---|
| `watch-sos` | **watch**, Wi‑Fi path | `POST` with headers `apikey: <anon>`, `Authorization: Bearer <anon>`, `x-device-token: <token>`; body `{"action":"start"\|"location"\|"cancel","incidentId","trigger","lat","lng","accuracyM","fixAt","test":true}` → `200 {"ok":true,"created":bool,"shareUrl":"…","telegramSent":n}`; `401` for a bad token. On a first `start` it sends a Telegram alert to linked contacts |
| `telegram-webhook` | Telegram | Links a contact when they send `/start <telegram_link_code>` to the bot |
| `digilocker-start?access_token=<user JWT>` | phone Custom Tab | Redirects to DigiLocker OAuth; the callback ends at `security4her://verified?ok=1\|0&reason=…`. Real flow needs `DIGILOCKER_CLIENT_ID/SECRET`; until then use `mock_verify_female()` |

## 4. SMS & share link (phone)

- Share URL: `${TRACK_BASE_URL}?k=${share_token}`.
- The SMS body stays under 3 SMS parts:
  `SOS from <name>. I may be in danger. Live location: <shareUrl> Last seen: https://maps.google.com/?q=<lat>,<lng> (±<acc>m, <HH:mm>)`
- In **Test Mode** (on by default), the police SMS and the auto-call go to the "test number" setting, never to 112.

## 5. Map feature (package inside `:mobile`)

- Entry point: `in.security4her.app.map.SafetyMapScreen(supabase: io.github.jan.supabase.SupabaseClient, modifier: Modifier = Modifier)`.
- It uses only `supabase.postgrest.rpc(...)`, Fused Location, and Maps Compose.
- `mobile/build.gradle.kts` must include `maps-compose`, `play-services-location`, `kotlinx-coroutines-play-services`, `activity-compose`, Compose `material3`, the supabase-kt `postgrest-kt` module, and the `kotlinx-serialization` plugin + `kotlinx-serialization-json` (versions above).
- Files: `map/MapRepository.kt` (RPC calls + `SafePlace`/`DangerCell` models) and `map/SafetyMapScreen.kt`. Host it inside your Scaffold content, with no extra top padding needed.
- The manifest must declare `ACCESS_FINE_LOCATION`, `CALL_PHONE` and the Maps API key meta-data.

## Change requests
_(append: date, who, what, why)_
- 2026-09-28: wrote the `mobile/` app. Offline mode when `SUPABASE_URL` is blank (local backend on the phone); the map uses OpenStreetMap without a Maps key.
- 2026-09-28: `wear/build.gradle.kts` and the §1 snippet — `java.util.Properties()` doesn't resolve in module scripts (the `java` extension shadows the package). Fixed with `import java.util.Properties`. Build on JDK 17 (Gradle 8.14.3 can't run on JDK 25).
- 2026-09-29: new trigger `voice` (watch heard "HELP HELP HELP", Voice SOS). Migration `20260929000004_more_triggers.sql` also allows `pocket` and `journey`, which the phone already sends.
