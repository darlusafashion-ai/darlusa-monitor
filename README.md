# DarLusa Monitor

Android companion app that reports (per DarLusa business day, Africa/Dar_es_Salaam, 08:00–07:59):

- Kariakoo arrival time (geofence excludes Mnazi Mmoja).
- Per-app foreground time and mobile MB for: WhatsApp, TikTok, Snapchat, Instagram, YouTube.
- Hotspot/tethering mobile MB.

No messages, chats, photos, contacts, or private content are collected.

## Build the APK (no PC required)

1. Open the **Actions** tab on this repository.
2. Open the latest "Build DarLusa Monitor APK" run.
3. Scroll to **Artifacts** → tap **DarLusaMonitor-debug-apk** to download the APK on your Android phone.
4. Open the downloaded file and tap **Install** (allow "Install unknown apps" if prompted).

## Setup on each company phone

1. Open the app.
2. Enter the employee name (e.g. `Michael`).
3. Grant **Usage Access** and **Location (Always allow)** when prompted.
4. Tap **Hifadhi & Anza** — the app starts reporting every 2 hours in the background.

The ingestion endpoint is `https://darlusafashion.lovable.app/api/public/device-metrics`.
