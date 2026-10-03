# QiblaCompass

A compass in the Android **status bar** with a small **arrow that points to the Kaaba (Qibla)**.

- Package: `QiblaCompass.blackfalcon.jan` — APK: `QiblaCompass.apk`
- **By: Black Falcon**

## What you see
- **Status-bar icon 1:** degrees on top, direction below (e.g. `245` / `SW`)
- **Status-bar icon 2:** a small arrow. "Up" is the way you are facing; the arrow turns to point at the Kaaba. When you face the Qibla (within 3°) a ring appears around the arrow.
- Pull the notifications down: `245° SW`, "Qibla: turn 47° right", distance to the Kaaba, and a **Stop** button.
- **Android 16+:** both are combined into one *Live Update* chip (arrow icon + `245°SW`). If it does not show, open the app and tap **Live updates settings**.

## Notes
- Location is needed to work out the Qibla direction. It is read when you open the app and saved on the phone; nothing is sent anywhere.
- Degrees are measured from **true north** (magnetic declination is corrected once the location is known), so the number and the arrow agree.
- Phone flat → "facing" = top of the phone. Phone upright → "facing" = back of the phone.
- Keep the phone away from magnets, metal and electronics, and calibrate by moving it in a figure-8 when the compass seems off.
- Runs as a foreground service, so it keeps working in the background. No internet, no dependencies. Android 8.0+.

## Build
Push to GitHub — the **Build APK** workflow runs automatically.
Open **Actions → latest run → Artifacts → QiblaCompass** to download `QiblaCompass.apk`.
