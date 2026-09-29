# OpenCode ACP spike — device runbook

Purpose: capture the **ground-truth** transcript of the real `opencode acp` so the
event mapping in `app/src/main/java/com/jarves/mh/runtime/OpenCodeAcpProtocol.kt` can be
verified against the actual client, not just the ACP spec.

Both scripts referenced here are already committed and syntax-checked, and the
Node driver has been smoke-tested end-to-end against a mock ACP server.

---

## What you get

- A live log in the app terminal.
- A raw nd-JSON transcript at `/workspace/acp-spike.ndjson`.
- A **summary** listing every `session/update` kind, every tool call, the session
  id, and the stop reason — the exact facts the bridge depends on.

The summary is the important part. Paste it back and I can lock the mapping in.

---

## Run 1 — already done (partial)

The first device run installed `opencode@1.18.33` and completed `initialize` and
`session/new`, then **crashed in the driver itself** before `session/prompt` was
sent. Cause: the driver called `JSON.stringify(x).slice(0, n)`, and
`JSON.stringify(undefined)` returns `undefined`. Real OpenCode returns neither
`availableCommands` nor `modes` (it returns `configOptions`), so that expression
threw. Fixed in `28fa450` with a `brief()` helper and re-verified against a mock
mimicking the real client shape.

**Confirmed by run 1:**

- `protocolVersion: 1`; capabilities include `loadSession`, `mcp http/sse`,
  `embeddedContext`+`image`, and session `close/fork/list/resume`.
- Auth is offered (`opencode-login`) but is **not required**.
- **The keyless free tier works.** `session/new` returns a `model`
  `configOptions` selector with `currentValue: opencode/big-pickle` and options
  that are all free Zen models — no login, no API key.
- A `mode` selector offers `build` and `plan`.

**What run 2 must capture:** the `session/update` stream — every kind, the tool
call shapes, permission option ids, and the stop reason. The fixed driver now
runs the full handshake to completion, so the same command is all that is needed.

---

## Before you start (one time, on the phone)

1. Open **PocketDev** and install the **core runtime** (Settings → Runtime → Install).
   Wait for it to reach "Installed". This gives you `node` and `npm` in the guest.

2. Get the two spike scripts onto the device. They live in the repo at:
   - `scripts/runtime-bundles/run-opencode-acp-spike.sh`
   - `scripts/runtime-bundles/opencode-acp-spike.js`

   Easiest: pull them over adb into the app's files dir, then copy into the guest
   workspace from the terminal. For example (from your computer):

   ```bash
   adb push scripts/runtime-bundles/run-opencode-acp-spike.sh /sdcard/
   adb push scripts/runtime-bundles/opencode-acp-spike.js /sdcard/
   ```

   Then, inside the app's **terminal**, move them into the guest workspace
   (the guest sees `/sdcard` through the same path, and `/workspace` is writable):

   ```bash
   mkdir -p /workspace/spike
   cp /sdcard/run-opencode-acp-spike.sh /sdcard/opencode-acp-spike.js /workspace/spike/
   ```

   (If `/sdcard` isn't mounted inside the guest, keep the two files wherever the
   terminal can read them and just call them by their real path.)

---

## Run the spike

In the app's **terminal**, run:

```bash
cd /workspace/spike
bash run-opencode-acp-spike.sh
```

The first run installs the pinned `opencode` (a couple of minutes on the guest;
it fetches the ARM64 binary via npm). Later runs skip straight to the handshake.

### Optional: exercise a real task and a specific model

```bash
# Ask it to actually look around the workspace (invokes tools + permissions):
PROMPT="List the files in /workspace and tell me how many you see." \
DURATION=120 \
bash run-opencode-acp-spike.sh

# Pin a free model (keyless, inside the real client):
MODEL=big-pickle bash run-opencode-acp-spike.sh
```

Any free model id from the Zen catalog works. These are keyless — that is the
whole point of running the genuine client.

---

## What to send back

Paste **the summary block** (between the `====` lines). If you can, also pull the
raw transcript:

```bash
adb exec-out run-as com.jarves.mh cat files/runtime/ubuntu/workspace/acp-spike.ndjson > acp-spike.ndjson
```

The summary is enough for most fixes. The full transcript helps if a `session/update`
shape is more complex than the spec suggests.

---

## Reading the summary

| Line | Why it matters |
|---|---|
| `session/update kinds` | The set of strings my parser must recognise. If a kind is missing here, I add it; if one appears that I didn't plan for, I handle it. |
| `tool calls` (title / kind / status) | How OpenCode labels tools, so the in-app approval card shows the right command. |
| `stopReason` | How a turn signals completion — currently I expect `end_turn`. |
| `permissions` | Whether `session/request_permission` fires and with which option ids (for the Approve/Deny buttons). |
| `fs read/write` | Whether the client leans on the app for file I/O (my bridge handles `fs/*` too). |
| `available slash commands` | **Disproven on the first run** — the real client returns no such field. Model and mode arrive as `configOptions` selectors instead, and the driver now prints them. |

If the free model returns a real (non-error) response to the default prompt, the
keyless-free-tier path is confirmed end-to-end and the app is ready for it.

---

## Troubleshooting

- **`node not found`** → the core runtime isn't installed. Finish step 1.
- **`npm install` fails** → the guest has no working network, or npm is pointed at a
  bad registry. Try `npm config get registry` in the terminal; it should be
  `https://registry.npmjs.org/`.
- **No `session/new` response** → the binary didn't start cleanly. The `[child stderr]`
  lines in the log will say why. Send me those.
- **Handshake works but no reply to the prompt** → try a longer `DURATION`; the first
  real prompt can take a while on a phone as the model loads.
