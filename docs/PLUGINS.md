# Writing plugins for Server Status

This document is the complete reference for plugins. It is written so that a human **or an AI**
can produce a working plugin from it without reading the app's source code.

A plugin is **one JSON file**. It declares

1. **where data comes from** (`source`): any HTTPS JSON API, a path on the configured Proxmox
   server, or the values the app already has;
2. **which values to read** from the response (`values`);
3. **how to draw them** on the round Glyph Matrix (`variants`).

Plugins contain no code and cannot run any. They can only make the GET request they declare
and draw what they read.

---

## 1. Install and use

* In the app, section **Plugins**: **Datei importieren** (pick a `.json` file) or
  **Aus Zwischenablage** (paste the JSON, e.g. straight from a chat with an AI).
* The app validates the plugin. If something is wrong you get a list of **all** problems with
  their location (e.g. `variants[1].text: Zeichen '€' gibt es in der Pixel-Schrift nicht`).
  Fix them and import again. Importing a plugin with an existing `id` replaces it.
* If the plugin declares `secrets`, the app asks for them right after import. They are stored
  encrypted on the phone and never inside the plugin file. Change them later with **Secrets**.
* A new plugin is switched on. Each installed plugin has a checkbox (rotation on/off) and
  **Entfernen**.
* Active plugins rotate **after the built-in views and before the clock**, with the same
  interval as everything else. A plugin that has nothing to show right now is skipped.
* **Jetzt testen** fetches every active plugin once and lists the values it read or the error.
* If the Proxmox server is DOWN, the DOWN sign replaces everything, plugins included.
  In night mode, plugins are not shown.

---

## 2. Minimal example

```json
{
  "format": 1,
  "id": "btc-price",
  "name": "Bitcoin in k€",
  "source": {
    "type": "http",
    "url": "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=eur",
    "every_minutes": 10
  },
  "values": {
    "k": { "path": "bitcoin.eur", "scale": 0.001 }
  },
  "variants": [
    { "if": { "error": true }, "design": "text", "text": "B?" },
    { "design": "text", "text": ["B{k:0}", "{k:0}"] }
  ]
}
```

More complete examples live in [`plugins/examples/`](../plugins/examples). Every file there is
validated and drawn by the unit tests, so they always match this document.

| File | Shows |
|---|---|
| `home-assistant-temperature.json` | HTTP API with a secret token, blinking warning above 28 |
| `proxmox-lxc-running.json` | Proxmox path, counting with a filter, ring design |
| `builtin-cpu-smiley.json` | New design for existing data: CPU load as pixel faces |
| `website-reachable.json` | Only cares whether a URL answers; uses the error variant |

---

## 3. Top level

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | Always `1`. |
| `id` | yes | Unique id: `a-z`, `0-9`, `-`, starts with a letter or digit, max 40 chars. |
| `name` | yes | Shown in the app, max 40 chars. |
| `secrets` | no | List of `{ "key": "token", "label": "Shown when asking" }`. `key`: `a-z 0-9 _`. |
| `source` | yes | Where the data comes from, see §4. |
| `values` | no | Named values read from the response, see §5. Not allowed with `builtin`. |
| `variants` | yes | Non-empty list; how to draw, see §6. |

Unknown fields are ignored.

---

## 4. `source`

All sources accept `every_minutes` (1–1440, default 1): the plugin is fetched at most that often.
The toy is woken by the phone **about once a minute** (always-on display), so 1 means "every
update". Data is fetched again right away when the plugin or its secrets change.

### 4.1 `"type": "http"`

```json
"source": {
  "type": "http",
  "url": "https://host:port/path?query",
  "headers": { "Authorization": "Bearer {secret:token}" },
  "fingerprint": "AB:CD:…",
  "format": "json",
  "every_minutes": 5
}
```

* `url` **must** start with `https://`. Plain `http://` is rejected (secrets would travel in
  clear text, and Android blocks it anyway).
* `headers`: optional. `{secret:KEY}` in `url` or any header value is replaced by the secret
  the user entered; every `KEY` must be declared in `secrets`.
* `fingerprint`: optional SHA-256 fingerprint of the server certificate (64 hex digits, colons
  optional). With it, **only** that exact certificate is accepted (use this for self-signed
  certificates). Without it the normal system CAs apply.
* `format`: `"json"` (default) or `"text"`. With `"text"` the trimmed body is the root value
  (path `$`).
* Only **GET** is supported. Timeout: 5 s. A non-2xx status, a timeout, invalid JSON or a
  missing secret counts as an **error** (see `{ "error": true }` in §6.1).

### 4.2 `"type": "proxmox"`

```json
"source": { "type": "proxmox", "path": "nodes/{node}/status", "every_minutes": 1 }
```

* GET on the Proxmox server configured in the app, with its API token and certificate pin.
* `path` is relative to `/api2/json/` (no leading slash needed).
* `{node}` is replaced by the first online node.
* The token's permissions apply (the recommended role PVEAuditor can read almost everything:
  `cluster/resources`, `cluster/tasks`, `nodes/{node}/status`, `nodes/{node}/rrddata?...`,
  `nodes/{node}/disks/list`, `nodes/{node}/storage`, ...). A 403 is an error.
* Proxmox wraps everything in `{"data": ...}`, so paths start with `data`.

### 4.3 `"type": "builtin"`

```json
"source": { "type": "builtin" }
```

No request. The plugin uses what the app already knows, which is how you build **new designs
for existing data**. There is no `values` section; use these names directly:

| Name | Type | Meaning |
|---|---|---|
| `up` | bool | Server reachable and all nodes online |
| `uptime_hours`, `uptime_days` | number | Whole hours / days since boot |
| `guests_running`, `guests_total`, `guests_stopped` | number | VMs + LXC (templates excluded) |
| `cpu`, `mem` | number | 0–100, highest node |
| `disk` | number | 0–100, all VM/LXC storages combined |
| `load` | number | 1-minute load average |
| `net_mbit` | number | Network in + out, Mbit/s |
| `backup_ok` | bool | Last finished backup job succeeded |
| `health_ok` | bool | No failed SMART check, all ZFS pools ONLINE |
| `quorate` | bool | Cluster quorum (missing on single nodes) |
| `updates` | number | Pending updates (needs a token with Sys.Modify) |
| `cpu_history`, `mem_history` | list | Last minutes, 0–100, oldest first (for `history`) |

The app sees which builtin values a plugin uses (in `if`, `value` and text templates) and
fetches exactly the data they need, even when the matching built-in view is switched off.
`health_ok` is refreshed every 30 minutes (SMART checks wake the disks). A value Proxmox does not
deliver (e.g. `quorate` on a single node, `updates` without Sys.Modify) stays missing.

---

## 5. `values`

```json
"values": {
  "temp":    { "path": "state" },
  "running": { "count": "data", "where": { "type": "lxc", "status": "running" } },
  "mem_gb":  { "sum": "data", "field": "mem", "scale": 0.000000001 }
}
```

Each value has exactly **one** of these keys:

| Key | Result |
|---|---|
| `"path": P` | The value at path P |
| `"count": P` | Number of elements of the array at P (after `where`) |
| `"sum"` / `"max"` / `"min"` / `"avg": P` | Over the array at P (after `where`), of each element's `field` |

* **Paths**: dot-separated keys with `[index]` for arrays: `data.loadavg[0]`,
  `data[2].tags[0].name`. `$` or `""` is the whole response.
* `field`: path inside each array element (required for sum/max/min/avg).
* `where`: object of `field-path: expected value`; an element is used only if **all** match.
  Comparison is loose: `"1"` equals `1`.
* `scale`: multiplies numeric results (e.g. `0.001` for k, `100` for 0–1 fractions).
* **Types**: JSON numbers and **numeric strings** (`"21.5"`) become numbers. `true`/`false`
  stay booleans. Other strings stay strings. A missing path gives no value.
* Value names: `a-z 0-9 _`, max 30 chars.

---

## 6. `variants`

The app picks the **first** variant whose `if` conditions all hold (a variant without `if`
always matches) and draws it. If no variant matches, or the chosen one cannot be drawn (missing
value, text too wide), the plugin is skipped in the rotation for now.

```json
{
  "if": [ { "value": "temp", "op": ">=", "than": 28 } ],
  "design": "text",
  "text": ["T{temp:0}", "{temp:0}"],
  "blink": true,
  "brightness": 255,
  "alert": true
}
```

| Field | Default | Meaning |
|---|---|---|
| `if` | – | One condition object or a list (all must hold), see §6.1 |
| `design` | required | `text`, `ring`, `history` or `icon`, see §6.2 |
| `text` | – | One template or a list of alternatives, see §7 |
| `value` | – | Value used by `ring` and `history` |
| `min`, `max` | 0, 100 | Range mapped onto 0–100 % for `ring` and `history` |
| `icon` | – | `{ "13": [...rows], "25": [...rows] }` for `icon`, see §6.3 |
| `blink` | false | Blink at 0.5 s (only noticeable if the phone redraws that often) |
| `brightness` | 255 | 1–255, scales the whole picture |
| `alert` | false | This state is a **warning**: with the app's alert mode on it is shown exclusively or interleaved, like built-in warnings |

### 6.1 Conditions

* `{ "error": true }` – the last fetch failed; `{ "error": false }` – it succeeded.
* `{ "value": "NAME", "op": OP, "than": X }` with `OP` one of `== != > >= < <=`.
  Numbers compare numerically; strings and booleans only with `==` / `!=`.
  A condition on a missing value is false.

Put the `error` variant **first**: after an error no values are available.

### 6.2 Designs

| Design | Draws | Needs |
|---|---|---|
| `text` | The first template alternative that fits, centered; `\n` stacks two lines | `text` |
| `ring` | The outer pixel ring filled clockwise from 12 o'clock to `(value-min)/(max-min)`; inside, the first `text` alternative that fits **without touching the ring** (on 13×13 at most 2 characters, e.g. `35`), else no text | `value`; `text` optional |
| `history` | Bars of the value's recent samples in the lower part, first `text` alternative as one line on top | `value`; `text` optional |
| `icon` | Your own pixel art | `icon` |

`history` uses a list value directly (builtin `cpu_history`, `mem_history`). For a number the
app records one sample per successful fetch (last 24) and draws the newest 9 (13×13) or
19 (25×25).

### 6.3 Icons

```json
"icon": {
  "13": [ "13 strings of 13 characters" ],
  "25": [ "optional: 25 strings of 25 characters" ]
}
```

* `#` = full brightness, `+` = half, `-` = dim, `.` = off.
* Every lit pixel must be **inside the round display** (see §8); otherwise import fails with the
  pixel's coordinates.
* Without `"25"`, the 13×13 icon is centered on the Phone (3)'s 25×25 matrix.

---

## 7. Text templates

* `{name}` inserts a value. Numbers: whole numbers without decimals, others with one.
  `{name:N}` forces N decimals (0–3, rounded half up). Booleans become `✓` / `✗`.
  Strings are inserted as they are.
* Give **alternatives** from long to short: `["T{temp:0}", "{temp:0}"]`. The first one that fits
  the display is used; if a value is missing the alternative is skipped.
* Lowercase letters are drawn as uppercase (except `d` and `h`, which have their own glyphs).
* Digits, `✓` and `✗` are drawn at full brightness, everything else dimmed, so a label letter
  plus a number reads well (`T21`).

**Characters available** (anything else is rejected at import):

```
0-9   A-Z   d h   / : . - + % ° ! ?   space   ✓ ✗
```

---

## 8. The display

| Phone | Matrix | Font | Glyph size | Fits on one line |
|---|---|---|---|---|
| Phone (4a) Pro | 13×13 | 3×5 | 3 px wide (`!` `.` `:` space: 1 px), 1 px gap | **3 characters** (`T21`, `LX7`, `99%`) |
| Phone (3) | 25×25 | 5×7 | 5 px wide (1–3 px for `! . : - °` and space), 1 px gap | **4 characters** |

Width of a line = sum of glyph widths + 1 px between glyphs. Text is centered.

The displays are **round**. Lit pixels outside the circle do not exist. Visible columns per row
of the 13×13 matrix:

| Row | 0 | 1 | 2 | 3 | 4–8 | 9 | 10 | 11 | 12 |
|---|---|---|---|---|---|---|---|---|---|
| Columns | 4–8 | 2–10 | 1–11 | 1–11 | 0–12 | 1–11 | 1–11 | 2–10 | 4–8 |

25×25: pixel (x, y) exists if `(x-12)² + (y-12)² ≤ 12.5²`.

Two stacked lines on 13×13 occupy rows 1–5 and 7–11, where only 9 columns are visible:
**2 characters per line** (`12` over `34`). Keep icons inside rows/columns 2–10 to be safe.

---

## 9. Checklist for authors (and AIs)

1. `format: 1`, a unique lowercase `id`, a short `name`.
2. `https://` only; secrets declared in `secrets` and used as `{secret:key}`; never put tokens
   into the file.
3. Proxmox paths start with `data` in `values` (the API wraps responses in `data`).
4. First variant: `{ "if": { "error": true }, ... }` so failures are visible.
5. Every text alternative ≤ 3 characters for the Phone (4a) Pro; give a shorter fallback.
6. Only the characters from §7 (lowercase letters are drawn as uppercase).
7. Icons: exactly 13 rows × 13 chars of `.#+-`, everything inside the circle (§8).
8. Mark critical states with `"alert": true`; use `"blink": true` sparingly.
9. Import, read the error list if any, then press **Jetzt testen** to see the values read.

## 10. Limits

* GET only, JSON or plain text, 5 s timeout, HTTPS only.
* No arithmetic between values (each value comes from one path or aggregate; `scale`
  multiplies by a constant).
* A plugin is drawn on the phone; it cannot change the server or anything else.
* Refreshes follow the phone's always-on updates (about once a minute).

## 11. For developers

| File | Role |
|---|---|
| `app/src/main/java/dev/lukas/glyphstatus/plugin/Plugin.kt` | Data model, parser, validation (all error messages) |
| `.../plugin/PluginEngine.kt` | Value extraction, conditions, templates, drawing; builtin values |
| `.../plugin/PluginRunner.kt` | Fetching sources |
| `.../plugin/PluginStore.kt` | Installed plugins, enabled flags, encrypted secrets, last state |
| `.../Screens.kt` | Rotation (`slots`), plugins become `PluginDisplay`s |
| `app/src/test/.../plugin/PluginTest.kt` | Tests, including every file in `plugins/examples/` |

To add a new example, drop a `.json` file into `plugins/examples/`; `PluginTest` parses it and
draws it on both display sizes, so `./gradlew testDebugUnitTest` tells you if it is valid.
