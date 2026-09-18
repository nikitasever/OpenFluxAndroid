# Release signing setup (one-time)

This repo builds an **unsigned debug APK** on every push (`build-apk.yml` →
`build` job), which is why every CI-rebuilt install has needed a full
uninstall/reinstall — the ephemeral runner regenerates the debug keystore
each run.

The `build-release` job in the same workflow builds a properly **signed
release APK** instead, so future installs update in place. It only runs once
four repo secrets exist; until then it's skipped automatically and the debug
job keeps working as before.

The keystore and its passwords never need to pass through an AI assistant,
a chat log, or a CI log — generate and register them yourself, once, with
the commands below.

## 1. Generate the keystore

Needs a JDK (`keytool` ships with any JDK 17+, e.g. Temurin, or the one
bundled with Android Studio under `<Android Studio>/jbr/bin`).

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias openflux \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype JKS
```

It will prompt for a store password, a key password (can be the same as the
store password), and the certificate's distinguished name (any values are
fine — they end up in the APK's signing cert, not shown to end users).

**Back up `release.jks` and its passwords somewhere durable** (password
manager). If it's lost, no future release can be an in-place update over
apps signed with it — everyone would need to uninstall and reinstall.

`release.jks` must never be committed — `.gitignore` already excludes
`*.jks`/`*.keystore`.

## 2. Register the four GitHub secrets

In your fork: **Settings → Secrets and variables → Actions → New repository
secret**, or via `gh` CLI from the same machine that holds the file:

```bash
gh secret set RELEASE_KEYSTORE_BASE64 --repo <you>/OpenFluxAndroid --body "$(base64 -w0 release.jks)"
gh secret set RELEASE_KEYSTORE_PASSWORD --repo <you>/OpenFluxAndroid
gh secret set RELEASE_KEY_ALIAS --repo <you>/OpenFluxAndroid --body "openflux"
gh secret set RELEASE_KEY_PASSWORD --repo <you>/OpenFluxAndroid
```

(The two password secrets omit `--body` so `gh` prompts interactively
instead of putting the password on your shell history / a script.)

## 3. Done

Push to `fluxon-feature-port` (or run the workflow manually) and the
`build-release` job produces a signed `openflux-release-apk` artifact.
Every future release built with the same keystore installs as an update
over the previous one — no more uninstall/reinstall.
