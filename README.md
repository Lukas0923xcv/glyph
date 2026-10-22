# Server Status – Glyph Toy für Proxmox

Glyph Toy für die Glyph Matrix (Nothing Phone (3) 25×25, Phone (4a) Pro 13×13).
Der Toy zeigt, ob dein Proxmox-Server erreichbar ist:

- **UP** (Anfrage erfolgreich und alle Nodes `online`): Häkchen, mittlere Helligkeit
- **DOWN** (Timeout nach 5 s, HTTP-Fehler, Node nicht online, kein Netz): X, volle Helligkeit, blinkt im 500-ms-Takt

Abgefragt wird `GET {host}/api2/json/nodes` beim Aktivieren des Toys und bei jedem AOD-Event (ca. 1× pro Minute).

## 1. API-Token in Proxmox anlegen

Lege einen eigenen User mit reinen Leserechten an, statt `root@pam` zu verwenden.

1. **Datacenter → Permissions → Users → Add**: User `glyph`, Realm `Proxmox VE authentication server` (`pve`).
2. **Datacenter → Permissions → Add → User Permission**: Path `/`, User `glyph@pve`, Role **PVEAuditor**, Propagate aktiviert.
3. **Datacenter → Permissions → API Tokens → Add**: User `glyph@pve`, Token ID `status`.
   Das Secret wird nur einmal angezeigt – sofort kopieren.

**Privilege Separation:** Ist sie beim Token aktiviert (Standard), hat der Token *keine* eigenen Rechte,
auch wenn der User welche hat. Dann musst du die Rolle zusätzlich dem Token geben:
**Permissions → Add → API Token Permission**, Path `/`, Token `glyph@pve!status`, Role `PVEAuditor`.
Alternativ Privilege Separation beim Anlegen deaktivieren; dann erbt der Token die Rechte des Users.
Ohne Rechte liefert `/nodes` eine leere Liste, und der Toy zeigt DOWN.

Test auf der Kommandozeile:

```sh
curl -k -H 'Authorization: PVEAPIToken=glyph@pve!status=<SECRET>' https://192.168.1.10:8006/api2/json/nodes
```

## 2. Zertifikat-Fingerprint auslesen

Proxmox verwendet ein selbstsigniertes Zertifikat. Damit die App es akzeptiert, wird es per SHA-256-Fingerprint gepinnt.

- GUI: **Datacenter → *Node* → System → Certificates**, dort den Eintrag `pveproxy-ssl.pem`
  (falls vorhanden, sonst `pve-ssl.pem`) markieren → **View Certificate** → Feld *Fingerprint* kopieren.
- Oder auf dem Node: `openssl x509 -in /etc/pve/local/pve-ssl.pem -noout -fingerprint -sha256`

Der Fingerprint kann mit oder ohne Doppelpunkte eingetragen werden. Die App akzeptiert dann **nur** genau
dieses Zertifikat. Bleibt das Feld leer, gelten die normalen System-CAs (z. B. für ein Let's-Encrypt-Zertifikat).
Wird das Zertifikat erneuert, musst du den neuen Fingerprint eintragen.

## 3. APK bauen, installieren und Toy aktivieren

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. App **Server Status** öffnen, Host-URL (z. B. `https://192.168.1.10:8006`), Token-ID (`glyph@pve!status`),
   Secret und Fingerprint eintragen → **Speichern** → **Jetzt testen**.
2. **Glyph Toys verwalten** antippen (oder *Einstellungen → Glyph Interface*) und *Server Status*
   von *Disabled* nach *Active* ziehen.
3. Für die Anzeige im Always-on-Modus: *Einstellungen → Glyph Interface → Flip to Glyph → Always-on Glyph Toy*
   → *Server Status* wählen. Auf dem Phone (4a) Pro gibt es nur AOD-Toys.

## Projekt

- `app/libs/glyph-matrix-sdk-2.0.aar` – Glyph Matrix SDK aus dem
  [GlyphMatrix-Developer-Kit](https://github.com/Nothing-Developer-Programme/GlyphMatrix-Developer-Kit)
- `StatusToyService` – Glyph-Toy-Service (Bind/Unbind, AOD-Event, Blinken)
- `ProxmoxClient` – HTTP-Abfrage (OkHttp, 5 s Timeout) und Zertifikat-Pinning
- `MatrixIcons` – Pixel-Icons für 25×25 und 13×13, alle innerhalb des runden Sichtbereichs
- `SettingsActivity` / `Storage` – Einstellungen; Zugangsdaten in EncryptedSharedPreferences
