# Paisa

A personal money tracker: accounts and transfers, months that start on payday,
a "from payout" switch that keeps extra-money spending out of the salary meter,
and a reports page.

All data stays on the device. Nothing is uploaded.

## Layout

- `docs/` is the app itself (one HTML page plus a spreadsheet library). It also works
  as an installable web app if GitHub Pages is switched on for `main` / `docs`.
- `android/` is a thin Android shell that packages `docs/` into an APK.
- `.github/workflows/android.yml` builds a signed APK on every push to `main`
  and publishes it under Releases.

## Signing

The workflow needs two repository secrets:

- `PAISA_KEYSTORE_B64`: the signing keystore, base64-encoded
- `PAISA_KEYSTORE_PASSWORD`: its password (key alias `paisa`)

Keep the keystore safe. Updates only install over the existing app when they are
signed with the same key.
