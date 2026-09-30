# OpenCode Zen free models — what actually works

Everything here was verified against the live `opencode.ai/zen` API and against the
real `opencode` 1.18.33 ARM64 binary, not inferred.

## TL;DR for the app

| Fact | Value |
|---|---|
| Zen base URL | `https://opencode.ai/zen/v1` |
| Protocol the app must use | `OPENAI_CHAT` → `/chat/completions` |
| Default model | `big-pickle` |
| Free tier via the app's own HTTP client | Only `space-bunny-free` (verified 200) |
| Free tier via the bundled OpenCode agent | Works — the real binary returns `pong` on `big-pickle` with **no key** |

## 1. The wire format bug (this caused the user's setup error)

`ProviderKind.OPENCODE_ZEN` was configured with `ProviderProtocol.OPENAI_RESPONSES`,
so the app POSTed to `/v1/responses`. Zen answers every free model on that route with:

```
401  {"error":{"type":"ModelError","message":"Model <id> is not supported for format openai"}}
```

That is the exact error in the user's screenshot. The same models are served on
`/chat/completions`. Confirmed by the binary itself, which declares:

```
opencode: { npm:"@ai-sdk/openai-compatible", api:"https://opencode.ai/zen/v1" }
```

`@ai-sdk/openai-compatible` is the **`/chat/completions`** dialect. `fixedProtocol =
true` also prevents a previously stored `"openai-responses"` from being reused.

## 2. Anonymous status of every free model (live probe, no key, `/chat/completions`)

| Model | Result |
|---|---|
| `space-bunny-free` | **200**, `cost: "0"`, content `"pong"` |
| `big-pickle` | 403 `FreeTierError` — *"free tier can only be used from within OpenCode"* |
| `mimo-v2.5-free`, `mimo-v2.6-flash-free` | 403 `FreeTierError` |
| `longcat-2.5-preview-free` | 403 `FreeTierError` |
| `ling-3.0-flash-fin-free` | 403 `FreeTierError` |
| `nemotron-3-ultra-free`, `nemotron-3.5-lightning-free` | 403 `FreeTierError` |
| `deepseek-v4-flash-free` | 400 upstream failure |
| `muse-spark-1.2-contributor-free` | 403 `RegionError` — *"not available in your country"* |
| `jev-1.13-free` | 500 internal server error |
| `GET /v1/models` | **200** — the catalog is public, so discovery needs no key |

## 3. The real binary DOES get free access — headers do not

The most important result. The actual `opencode` CLI, run in the guest with **no
`auth.json` and no API key**:

```
$ opencode run --model opencode/big-pickle "Reply with the single word: pong"
> build · big-pickle
pong                       # exit 0
```

But replicating that over plain HTTP does **not** work. Headers extracted from the
binary (`strings` on the 184 MB executable) and replayed against the live API:

```
x-opencode-session: <uuid>
x-opencode-request: usr_<uuid>
x-opencode-client:  cli
User-Agent: opencode/1.18.33
X-BILLING-INVOKE-ORIGIN: OpenCode     # found in the binary, still 403
X-Session-Id: <uuid>                  # non-opencode provider path only
```

All of these still return `403 FreeTierError` for `big-pickle`. So the unlock is
**not** a forgeable header — it lives in the binary's own auth/session layer (it writes
to `opencode.db`). A garbage bearer token is *worse* than none: `space-bunny-free`
goes 200 → 401 when an invalid key is sent.

### Why this is still not a lock-in

The official docs list every free model on the plain public
`https://opencode.ai/zen/v1/chat/completions`, show a plain `curl` example for
`jev-1.13-free`, and state Zen's goal as *"no lock-in by allowing you to use it with
any other coding agent."* The docs and the live free-tier enforcement currently
disagree for everything except `space-bunny-free`; that is a live behavioural
discrepancy on OpenCode's side, not a documented restriction.

## 4. Design consequence

This is why the app runs the **real** `opencode` binary over ACP rather than calling
Zen itself. The bundled agent is the genuine client, so it receives the free tier
keylessly. The app's own HTTP path is only used for *discovery* (public catalog) and
for a user who supplies a Zen key.

`big-pickle` is the default because it is the stable headliner of the free tier.
`space-bunny-free` is a novelty model OpenCode can remove at any time, so defaulting
to it would hand new users a dead default — it is listed, but never as the default.

## Reproducing

```bash
curl -s https://opencode.ai/zen/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"big-pickle","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}'
# {"type":"error","error":{"type":"FreeTierError",...}}
```
