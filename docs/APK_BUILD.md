# Building a runnable APK (Cloud / CI)

## TL;DR

The native engine (proot, prootloader, `libpocketspawn`) is cross-compiled by the
Android NDK, whose host tools are **x86_64 only**. So a runnable APK must be
built on an **x86_64** machine — GitHub Actions, Codespaces, or a laptop. The
phone (aarch64) can only produce a Kotlin-only build, which is not runnable.

Two ways, both using the **same** bootstrap script so there is one source of
truth for provisioning:

- **Cloud (recommended, zero setup):** push to your own GitHub repo → the
  `Build APK` workflow runs on an x86_64 runner → download the APK artifact.
- **Any x86_64 machine:** `bash scripts/bootstrap-and-build-apk.sh`.

## Option 1 — GitHub Actions (push to YOUR repo)

The cloned repo's only remote is the **original author's**
(`techjarves/Mobile-Harness`). Do **not** push to it. Point it at your own:

```bash
# from the project root
git remote add mine git@github.com:<YOUR_USER>/<YOUR_REPO>.git   # or the https URL
git push -u mine main
```

Pushing triggers `.github/workflows/build-apk.yml` automatically. Then:

1. Open **your** repo on GitHub → the **Actions** tab → the `Build APK` run.
2. When it finishes (green), open it and download the **pocketdev-apk**
   artifact (it is a zip containing the APK).
3. Install that APK on your phone.

You can also trigger it on demand via the **Run workflow** button, and every
published **Release** gets the APK attached automatically.

> Note: the first run downloads the NDK (~640 MB) and builds proot from source,
> so allow ~15–30 minutes. Subsequent runs use the Gradle cache and are faster.

## Option 2 — Any x86_64 machine (Codespaces, laptop, VM)

```bash
git clone <YOUR_REPO> && cd Mobile-Harness
bash scripts/bootstrap-and-build-apk.sh
```

The script installs everything it needs (JDK 17, Android SDK + build-tools,
NDK r26d, CMake 3.22.1), clones the pinned native submodules, writes
`local.properties`, and runs `:app:assembleOnlineDebug`. It is idempotent and
resumable, so re-running after any interruption continues where it stopped.

The APK lands under `app/build/outputs/apk/`.

Useful flags:

```
--tasks <gradleTask>   build a different target (default :app:assembleOnlineDebug)
--dry-run             report what would happen; change nothing
--offline             assume the toolchain is already provisioned
```

## What the script provisions (keep in sync with app/build.gradle.kts)

| Input | Version / pin |
|---|---|
| JDK | 17 |
| Gradle | 8.14 (wrapper) |
| AGP / Kotlin | 8.13.2 / 2.2.21 |
| compileSdk | 36 |
| build-tools | 35.0.0 |
| NDK | 26.1.10909125 (= r26d) |
| CMake | 3.22.1 |
| proot submodule | termux/proot @ `61681c6` |
| libandroid-shmem submodule | termux/libandroid-shmem @ `7f0bd7e` |

## Architecture behaviour

The script prints the host architecture and gates the native layer:

- **x86_64** → native engine compiled → runnable APK.
- **aarch64** → the NDK's x86_64 clang cannot run, so it builds with
  `-PmhNativeBuild=false` (Kotlin + resources only). Useful as a compile check;
  **not** runnable, and it says so explicitly.

The CI workflow additionally asserts the APK actually contains
`libpocketspawn.so`, `libproot.so` and `libprootloader.so` before calling it a
success — so a native-less build can never be mistaken for a working one.
