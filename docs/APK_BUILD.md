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

> **If the push is rejected because your token lacks the `workflow` scope:**
> GitHub requires that scope to push any `.github/workflows/*.yml` file. You do
> **not** need it to back up your code — push the repo *without* the workflow
> file first, then add the workflow through the GitHub web UI (which needs no
> extra scope):
>
> ```bash
> git reset --soft HEAD~1
> git reset HEAD .github/workflows/build-apk.yml   # keep it on disk, unstaged
> git commit -m "Add build bootstrap"               # everything else
> git push -u mine main                            # ✅ code is now backed up
> ```
>
> Then open your repo on GitHub → **Add file → Create new file**, set the path to
> `.github/workflows/build-apk.yml`, paste the workflow contents, and commit.
> The `Build APK` workflow will then run.
>
> To grant the scope instead: `gh auth refresh -h github.com -s workflow`
> (opens https://github.com/login/device; the one-time code is valid ~15 min).
>
> **The REST API is not a way around it.** Creating the file via
> `gh api -X PUT repos/<owner>/<repo>/contents/.github/workflows/...` returns
> **HTTP 404** even with a token that can write every other path — GitHub applies
> the same `workflow`-scope gate to the Contents API, and uses 404 (not 403) so
> the path isn't disclosed. The gist + web-UI route above is the shortest
> permission-free path.

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

## Installing side-by-side (keeping an existing PocketDev)

Android identifies an app by `applicationId`. Building with the **same** id as an
app you already have means Android tries to *replace* it — and refuses outright if
the two APKs are signed with different keys ("existing package has different
signatures"). To keep both installed, give the build a different id:

```bash
scripts/bootstrap-and-build-apk.sh \
  --app-id io.github.bereket369g.pocketdev \
  --app-label "PocketDev OpenCode"
```

Or directly, for the CI build (this is what `.github/workflows/build-apk.yml`
does):

```bash
gradle :app:assembleOnlineDebug \
  -PmhApplicationId=io.github.bereket369g.pocketdev \
  -PmhAppLabel="PocketDev OpenCode"
```

- `applicationId` — the Android identity. A different value gives the build its
  own icon, data dir and permissions, so it installs alongside the original and
  **your existing chats, projects and API keys are untouched**.
- `appLabel` — the launcher label, so the two icons are tellable apart.

Both default to the upstream values (`com.jarves.mh` / "Mobile Harness"), so a
build with no flags behaves exactly as before. Nothing else needs to change: the
Kotlin `com.jarves.mh.*` packages are internal names, the `FileProvider`
authority is derived from `${applicationId}`, and the app sets `PROOT_TMP_DIR`
to its own `cacheDir` at runtime, so the native layer follows the new id too.

## Verified build record

Run `36604202190` on `Bereket369G/Mobile-Harness` completed **success** in
6m58s. The produced APK was downloaded and independently checked:

| Check | Result |
|---|---|
| Package / version | `com.jarves.mh` 1.0.4 (code 5) |
| compileSdk / targetSdk | 36 / 28 (direct-APK path, as designed) |
| ABI | `arm64-v8a` only |
| `lib/arm64-v8a/libpocketspawn.so` | present (9,352 B) |
| `lib/arm64-v8a/libproot.so` | present (256,808 B) |
| `lib/arm64-v8a/libprootloader.so` | present (7,504 B) |
| `libtalloc.so` / `libandroid-shmem.so` | present |
| APK size | 62,780,448 B |
| sha256 | `289da6f56c11238e9a7a9016034437847d554c705e7759cdccdfd7543c07a464` |

OpenCode support confirmed compiled into `classes7.dex`: `OpenCodeRuntimeBridge`,
`OpenCodeAcpProtocol`, `OpenCodeAcpEvent`, `ComposerAutocomplete`,
`buildComposerSuggestions`, and the `SLASH_COMMANDS` / `FILE_MENTIONS`
capabilities.

The `libproot.so` / `libprootloader.so` entries are the "executable carrier"
trick working as intended — the CMake carrier `.so` is overwritten at build time
with the real PRoot binary, which AGP then packages as a shared library.

### Known limitation

`OPENCODE_BUNDLE.sha256` is intentionally still empty, so the in-app installer
refuses to install the OpenCode bundle with an actionable message. The bundle
(`.tar.zst`) has to be produced on an ARM64 host that already has the runtime
installed, using `scripts/runtime-bundles/build-opencode-from-installed-android.sh`,
then its checksum filled in. The APK is otherwise complete and runnable.
