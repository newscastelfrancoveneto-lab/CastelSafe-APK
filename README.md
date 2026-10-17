# CastelSafe per Android

App nativa di [CastelSafe](https://newscastelfrancoveneto-lab.github.io/CastelSafe/) per i cittadini di Castelfranco Veneto: allerte meteo-idro, sensori ARPAV, mappa delle aree di protezione civile, avvisi e chiusure, numeri di emergenza.

**Scarica l'APK:** [ultima release](https://github.com/newscastelfrancoveneto-lab/CastelSafe-APK/releases/latest). L'app si aggiorna da sola dalla stessa pagina.

## Come funziona

- Kotlin + Jetpack Compose, mappa con osmdroid (OpenStreetMap), nessun servizio Google.
- I dati arrivano dal server CastelSafe (Raspberry): `/api/allerta`, `/api/monitor/stato`, `/api/closures`, `/api/arpav/...`.
- Notifiche: l'app legge ogni ~15 minuti (WorkManager) e a ogni apertura il registro `/api/notifiche/feed`, che il server riempie con ogni notifica inviata alla PWA. I filtri (categorie, livello minimo, stazioni, suono, ore di quiete) sono applicati sul telefono.

## Pubblicare una versione

1. Aumenta `versionCode` e `versionName` in `app/build.gradle.kts` e scrivi `fastlane/metadata/android/it-IT/changelogs/<versionCode>.txt`.
2. `git tag vX.Y.Z && git push --tags`: il workflow *Release* compila, firma e pubblica `CastelSafe-X.Y.Z.apk`.

La firma usa i secret `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. La chiave non deve mai cambiare, altrimenti gli aggiornamenti non si installano sopra la versione esistente.
