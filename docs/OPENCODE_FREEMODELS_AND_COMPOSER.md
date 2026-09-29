# OpenCode follow-ups: free models + composer shortcuts (`/`, `@`)

Two questions, both answered from the live OpenCode docs + the current
PocketDev source. Companion to `OPENCODE_AGENT_PLAN.md`.

---

## Q1 — Can we get the free models OpenCode provides *without any API key*?

**Yes — and your PC experience is exactly right.** You install the real OpenCode
CLI, run it, and the free models work with no sign-in and no key. The catch is
that this works *only from inside the genuine `opencode` binary*; the free tier
is gated server-side to the real client. So the app can reproduce the keyless
free experience **if and only if we bundle and run the real `opencode` binary**
(which is the plan). PocketDev cannot call Zen's free models itself. *(This
corrects an earlier version of this addendum, which wrongly claimed a Zen key was
always required — it is not. It also wrongly claimed the app's existing
User-Agent spoof already unlocked the free models — it does not.)*

### The free models (verified, https://opencode.ai/docs/zen/) — cost $0.00

Zen currently lists 11 **free** models (per-request cost $0.00 in/out):

| Model | Model id | Endpoint |
| --- | --- | --- |
| Big Pickle | `big-pickle` | `/zen/v1/chat/completions` |
| Space Bunny Free | `space-bunny-free` | `/zen/v1/chat/completions` |
| LongCat 2.5 Preview Free | `longcat-2.5-preview-free` | `/zen/v1/chat/completions` |
| MiMo-V2.6-Flash Free | `mimo-v2.6-flash-free` | `/zen/v1/chat/completions` |
| MiMo-V2.5 Free | `mimo-v2.5-free` | `/zen/v1/chat/completions` |
| Ling 3.0 Flash Fin Free | `ling-3.0-flash-fin-free` | `/zen/v1/chat/completions` |
| Nemotron 3 Ultra Free | `nemotron-3-ultra-free` | `/zen/v1/chat/completions` |
| Nemotron 3.5 Lightning Free | `nemotron-3.5-lightning-free` | `/zen/v1/chat/completions` |
| Muse Spark 1.3 Contributor Free | `muse-spark-1.3-contributor-free` | `/zen/v1/responses` |
| Jev 1.13 Free | `jev-1.13-free` | `/zen/v1/systemone` (structured decisions, not chat) |

In OpenCode config they're referenced as `opencode/<id>` (e.g.
`opencode/big-pickle`). Almost all are `chat/completions` (OpenAI-compatible),
`responses` (OpenAI), or `messages` (Anthropic) — which matters because the
app's `ProviderApiClient` + `LocalFormatGateway` already speak all three
shapes, and the existing `ProviderKind.OPENCODE_ZEN` provider already points at
`https://opencode.ai/zen/v1/...`.

### How the free tier is actually gated (live-verified, not from docs)

I probed `https://opencode.ai/zen/v1/...` directly. Three facts, which together
define exactly what the app must do:

| Request | Result |
|---|---|
| `GET /v1/models`, no key, any UA | **200** — the catalog is public (no key needed to *browse* models) |
| `POST /chat/completions` (free model), **no** key, spoofed `User-Agent: opencode/1.18.33` + `x-session-id` | **403** `{"type":"FreeTierError","message":"...can only be used from within OpenCode"}` |
| `POST /chat/completions`, **any** Bearer key (even invalid) | **401** `{"type":"AuthError","message":"Invalid API key."}` |

So: **discovery is open, inference is gated, and the gate is not a header you can
spoof.** The keyless free path is reachable *only* from the real `opencode`
binary (it presents an internal credential the server accepts) — precisely the
CLI behaviour you saw on the PC. A pasted Zen key is the *other* door (a valid
key authenticates and the free models then cost $0), but it is not required for
the keyless experience.

**This is the single most important consequence for the app:** the free models
work keylessly *because* the real OpenCode binary is the caller. So we must ship
and run the real `opencode` in the guest (Step 1 of the plan) and drive it over
ACP. The app must **not** try to call Zen's free models itself — that path is
closed (and the app's existing `User-Agent: opencode/1.18.20` spoof at
`ProviderApiClient.kt:156-160` does **not** open it — that exact header, sent to
the live endpoint, still returns 403).

### What the app should do with OpenCode's model list (the "free tier" UX)

Because the keyless free models are a *first-class* OpenCode provider, the
OpenCode model picker inside the app should surface a clearly labelled **"Free
· no key required"** grouping, driven by the public `/v1/models` catalog (which
is open, so no key is needed just to list them) — and the actual free inference
happens through the bundled `opencode` binary, not through the app. Users who
*want* to bring their own Zen key (or a paid model) can still paste one into
`ApiKeyVault` and use the existing `ProviderKind.OPENCODE_ZEN` OpenAI-responses
path. This gives the best of both: the keyless PC-like experience by default,
plus an optional key for paid routes.

### Two genuinely keyless *alternatives* (also no account, no key, and not Zen)

1. **Local models (truly offline & keyless).** The guest is a full Ubuntu ARM64
   userspace, so `llama.cpp` (CPU build) or `Ollama` (Linux ARM64) can run
   small open-weight coding models (Qwen, DeepSeek distill, etc.) in the guest
   with **zero API keys** and no network. OpenCode supports custom/local
   providers, so it would consume them natively. Trade-offs: slow on a phone,
   modest quality, extra bundle size + a running server in the guest. This is
   the real "no key anywhere, no network" option.
2. **Other providers' free tiers.** Not literally keyless (each still needs its
   own key), but many (e.g. OpenRouter free models, GitHub Copilot with a
   subscription) have recurring free usage. These already exist in the app's
   `ProviderKind` list; OpenCode would just gain them "for free" because it
   already supports 75+ providers via models.dev.

**Recommendation for the plan:** the keyless free experience is the *default*
because it comes free with running real OpenCode (no key, no account — matches
the PC). Do not gate it behind a Zen key. If a user pastes a Zen key, use it
for paid routes / the app-side `OPENCODE_ZEN` path. Note the local-model path as
a follow-up.

---

## Q2 — Adding `/`, `@`, and other composer shortcuts

**Good news: this is the highest-leverage, most reusable piece of work, and it
is currently missing for *all* agents in the app.** It's a shared-UI feature, not
an OpenCode-specific one.

### What exists today (verified in-source)

- **No autocomplete/mention/slash infrastructure anywhere.** A grep across all
  `*.kt` for `slashCommand|SlashCommand|mention|Mention|autocomplete` returns
  **zero matches**.
- The chat composer is a bare `BasicTextField` on a **plain `String`**
  (`ui/PocketDevApp.kt:4609-4635`): `onValueChange = { prompt = it }`. Because
  it's a `String` (not a `TextFieldValue`), the app has **no cursor/selection
  position**, which is exactly what an autocomplete popup needs to know where to
  insert a completion.
- **There IS a precedent to copy.** The terminal composer already uses
  `TextFieldValue` with selection + history (`ui/TerminalScreen.kt:114,142,148,
  395-465`). The chat composer should be upgraded to the same `TextFieldValue`
  pattern, then layer autocomplete on top.
- The approval/diff/attachment UI we'd want to feed already exists and is
  reusable: `RuntimeEvent.ToolRequested`/`ToolApproved`/`ToolRejected`,
  `RuntimeEvent.FilesChanged`, and `pendingAttachments`/`ChatAttachment`
  (`model/Models.kt:264-306, 319-325`).

### What OpenCode/ACP gives us for free (verified, https://opencode.ai/docs/acp/)

- `opencode acp` supports **custom tools and slash commands**, MCP servers,
  `AGENTS.md` rules, formatters/linters, agents, and the permissions system.
  Only `/undo` and `/redo` are unsupported over ACP.
- ACP's `session/new` response advertises the agent's **available slash
  commands** (`availableCommands`). So the app can **populate the `/` menu
  dynamically** from what OpenCode actually supports — no hardcoded list to
  drift. (Verify the exact field name against the ACP spec during the Step-1
  spike.)

### Design (shared, capability-gated — benefits Claude/DSH/Antigravity too)

Build **one reusable "rich composer" layer** used by every agent, gated by new
capability flags on `AgentDriver`/`AgentRegistry`:

1. **Upgrade the composer** `String` -> `TextFieldValue` (mirroring
   `TerminalScreen`), tracking cursor/selection. Purely additive; existing send
   logic keeps working.
2. **Trigger detection.** Parse the text before the cursor for an active token:
   - `/` at start-of-word -> slash-command popup
   - `@` at start-of-word -> file/mention popup (fuzzy-filter the project file
     list, which the app already has via project browsing)
   Popup positioning comes from the cursor offset.
3. **Completion behavior.**
   - `/name` -> insert/resolve the command and send it (OpenCode executes custom
     commands server-side; we just deliver the text).
   - `@pat` -> insert the file path (and/or route it through the existing
     `pendingAttachments`/`ChatAttachment` machinery so images/text attach the
     same way the paperclip does). OpenCode/ACP resolves the mention
     server-side; the client-side autocomplete is a UX convenience, not a
     correctness requirement.
4. **Capability gating.** Add e.g. `SLASH_COMMANDS` and `FILE_MENTIONS` to
   `AgentCapability`. OpenCode (ACP) = both ON. For the other agents, wire only
   what they genuinely accept (e.g. Claude Code supports `/`; `@` file mentions
   depend on the driver) and **degrade gracefully** — the popup simply never
   triggers when unsupported. This keeps the shared UI safe for all agents.

### Why ACP makes `/` and `@` "just work" for OpenCode

Because `opencode acp` is the *genuine* OpenCode client, the slash commands,
custom tools, MCP, and permissions are the real thing — not re-implementations.
Our app supplies the editor-side surface (autocomplete + send) that a TUI would
otherwise provide. This is precisely the "make it work exactly like the other
harnesses" parity you're after.

---

## Net effect on the plan

- **Q1 changes the plan's core justification:** the Zen free models are the
  strongest reason to bundle the *real* `opencode` binary, because the keyless
  free tier is gated to the genuine client. Surface them as a clearly labelled
  **"Free · no key required"** group in the OpenCode model picker (populated
  from the public `/v1/models` catalog, which needs no key to browse), and let
  the bundled binary do the actual keyless inference. Keep a Zen key as an
  *optional* path for paid models via the existing `ProviderKind.OPENCODE_ZEN`.
  Plus an optional local-model (llama.cpp/Ollama) follow-up for a truly offline
  mode.
- **Q2 adds** a **new, shared Step 2.5 — "Rich composer"** (TextFieldValue
  migration + `/` & `@` autocomplete + capability flags) that sits alongside the
  OpenCode work but is agent-agnostic and independently shippable. It is the
  most user-visible win and the most reusable code in this whole effort.

## Still to verify on a real device (the first spike)

- Exact ACP `availableCommands` field + the `/` & `@` round-trip over stdio.
- That the real `opencode` binary, running keyless in the guest, actually gets
  a zero-cost response from a free model (the live API confirms the *gate*; only
  the genuine client can pass it, so this is the end-to-end proof).
- Whether Zen free models require a card/billing details on file at all (docs
  mention "add billing details") — relevant only to the optional *keyed* path,
  not to the keyless default.
