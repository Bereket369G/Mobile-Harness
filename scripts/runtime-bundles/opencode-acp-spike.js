#!/usr/bin/env node
'use strict';
/*
 * OpenCode ACP spike driver -- runs INSIDE the PocketDev Ubuntu guest.
 *
 * Purpose: capture the ground-truth transcript of `opencode acp` so the event
 * mapping in app/src/main/java/com/jarves/mh/runtime/OpenCodeAcpProtocol.kt can
 * be verified against the real client instead of the ACP spec.
 *
 * This deliberately speaks the same handshake and the same JSON-RPC framing as
 * OpenCodeRuntimeBridge.kt, so a successful run here exercises the same wire
 * traffic the app will produce.
 *
 * Usage (in the guest):
 *   node /path/to/opencode-acp-spike.js [--prompt TEXT] [--model ID] [--seconds N]
 *
 * Outputs:
 *   - a human-readable log on stderr (visible in the app terminal)
 *   - a raw nd-JSON transcript at $OPENCODE_SPIKE_OUT (default /workspace/acp-spike.ndjson)
 *   - a summary of every `session/update` kind observed, plus the session id,
 *     stop reason, and every tool call seen -- the facts the bridge depends on.
 */

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

// ---------------------------------------------------------------- arguments

function argValue(flag, fallback) {
  const i = process.argv.indexOf(flag);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

const PROMPT = argValue('--prompt', 'Reply with the single word: pong');
const MODEL = argValue('--model', ''); // empty = whatever the client's default is
const SECONDS = parseInt(argValue('--seconds', '90'), 10);
const CWD = argValue('--cwd', process.env.WORKSPACE || '/workspace');
const BINARY = argValue('--binary', process.env.OPENCODE_BIN || 'opencode');
const OUT = process.env.OPENCODE_SPIKE_OUT || path.join(CWD, 'acp-spike.ndjson');

// ---------------------------------------------------------------- logging

const rawLines = [];
const updateKinds = new Map();   // sessionUpdate kind -> count
const toolCalls = [];
const methodsSeen = new Map();
const unknownKinds = new Set();

function log(...args) {
  try {
    process.stderr.write(args.join(' ') + '\n');
  } catch (_) { /* terminal closed */ }
}

/*
 * Safe "JSON then truncate" helper.
 *
 * JSON.stringify(undefined) returns undefined (not the string "undefined"), so
 * the naive `JSON.stringify(x).slice(0, n)` idiom throws a TypeError the moment
 * an optional field is absent -- which is exactly how the first spike run died
 * on `session/new`. Everything optional goes through this instead.
 */
function brief(value, n) {
  let s;
  try {
    s = typeof value === 'string' ? value : JSON.stringify(value);
  } catch (e) {
    s = String(value);
  }
  if (typeof s !== 'string') s = String(s);
  return n > 0 && s.length > n ? s.slice(0, n) : s;
}

function record(obj) {
  const line = JSON.stringify(obj);
  rawLines.push(line);
}

function flush() {
  try {
    fs.writeFileSync(OUT, rawLines.join('\n') + (rawLines.length ? '\n' : ''));
  } catch (e) {
    log('!! could not write transcript:', e.message);
  }
}

// ---------------------------------------------------------------- ACP client

let nextId = 1;
const pending = new Map();       // JSON-RPC id -> resolve
let sessionId = null;
let stopReason = null;
let turnEnded = false;
let permissionCount = 0;
let fsReadCount = 0;
let fsWriteCount = 0;

const child = spawn(BINARY, ['acp'], {
  cwd: CWD,
  stdio: ['pipe', 'pipe', 'pipe'],
  env: process.env,
});

log('== opencode acp spike ==');
log('binary :', BINARY);
log('cwd    :', CWD);
log('prompt :', JSON.stringify(PROMPT));
if (MODEL) log('model  :', MODEL);
log('out    :', OUT);
log('');

let stdoutBuf = '';
child.stdout.setEncoding('utf8');
child.stdout.on('data', (chunk) => {
  stdoutBuf += chunk;
  let idx;
  while ((idx = stdoutBuf.indexOf('\n')) >= 0) {
    const line = stdoutBuf.slice(0, idx).trim();
    stdoutBuf = stdoutBuf.slice(idx + 1);
    if (line) onLine(line);
  }
});
child.stderr.setEncoding('utf8');
child.stderr.on('data', (d) => {
  const s = d.trim();
  if (s) log('   [child stderr]', s.split('\n').join('\n   [child stderr] '));
});

function write(obj) {
  try {
    child.stdin.write(JSON.stringify(obj) + '\n');
  } catch (e) {
    log('!! write failed:', e.message);
  }
}

function request(method, params) {
  const id = nextId++;
  const p = new Promise((resolve) => pending.set(id, resolve));
  write({ jsonrpc: '2.0', id, method, params });
  return p;
}

function notify(method, params) {
  write({ jsonrpc: '2.0', method, params });
}

function onLine(line) {
  record({ _raw: line });
  let msg;
  try {
    msg = JSON.parse(line);
  } catch (e) {
    log('?? non-JSON line from child:', line.slice(0, 300));
    return;
  }

  if (msg.id !== undefined && msg.method === undefined) {
    // response to one of our requests
    const resolver = pending.get(msg.id);
    if (resolver) {
      pending.delete(msg.id);
      resolver(msg.result);
    } else {
      log('   (response to unknown id', msg.id + ')');
    }
    return;
  }

  methodsSeen.set(msg.method, (methodsSeen.get(msg.method) || 0) + 1);

  switch (msg.method) {
    case 'session/update': {
      const up = (msg.params && msg.params.update) || {};
      const kind = up.sessionUpdate || '(no sessionUpdate field)';
      updateKinds.set(kind, (updateKinds.get(kind) || 0) + 1);
      if (kind === 'tool_call' || kind === 'tool_call_update') {
        toolCalls.push({
          kind,
          toolCallId: up.toolCallId,
          title: up.title,
          kindField: up.kind,
          status: up.status,
          raw: up,
        });
        log('   [tool]', up.kind || '', '|', up.title || up.toolCallId || '');
      } else {
        log('   [update]', kind, summarizeUpdate(up));
      }
      break;
    }
    case 'session/request_permission': {
      permissionCount++;
      const p = msg.params || {};
      const options = p.options || [];
      log('   [permission]', p.toolCall && p.toolCall.title, '| options:',
        options.map((o) => o.optionId || o.name).join(', '));
      // Prefer an "allow once"-style option; fall back to the first.
      const allow = options.find((o) => {
        const k = String(o.kind || '').toLowerCase();
        return k.includes('allow');
      }) || options.find((o) => String(o.optionId || '').toLowerCase().includes('allow')) || options[0];
      const optionId = allow ? (allow.optionId || allow.name) : null;
      log('   [permission] auto-choosing', optionId);
      write({
        jsonrpc: '2.0',
        id: msg.id,
        result: { outcome: { outcome: optionId ? 'selected' : 'cancelled', optionId } },
      });
      break;
    }
    case 'fs/read_text_file': {
      fsReadCount++;
      const p = msg.params || {};
      try {
        const text = fs.readFileSync(p.path, 'utf8');
        write({ jsonrpc: '2.0', id: msg.id, result: { content: text } });
        log('   [fs read]', p.path, `(${text.length} chars)`);
      } catch (e) {
        write({ jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: e.message } });
        log('   [fs read FAILED]', p.path, e.message);
      }
      break;
    }
    case 'fs/write_text_file': {
      fsWriteCount++;
      const p = msg.params || {};
      try {
        fs.writeFileSync(p.path, p.content == null ? '' : p.content);
        write({ jsonrpc: '2.0', id: msg.id, result: {} });
        log('   [fs write]', p.path);
      } catch (e) {
        write({ jsonrpc: '2.0', id: msg.id, error: { code: -32000, message: e.message } });
        log('   [fs write FAILED]', p.path, e.message);
      }
      break;
    }
    case 'session/error':
      log('   [session/error]', brief(msg.params, 400));
      break;
    default:
      log('   [method]', msg.method, brief(msg.params || {}, 300));
  }
}

function summarizeUpdate(up) {
  try {
    if (up.content && up.content.text) return JSON.stringify(up.content.text).slice(0, 160);
    if (up.text) return JSON.stringify(up.text).slice(0, 160);
  } catch (_) { /* ignore */ }
  return '';
}

// ---------------------------------------------------------------- run

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

(async () => {
  // Mirrors the bridge's handshake ordering exactly.
  const initParams = {
    protocolVersion: 1,
    clientCapabilities: {
      fs: { readTextFile: true, writeTextFile: true },
    },
  };
  if (MODEL) initParams.clientCapabilities.__model = MODEL; // recorded only

  log('--> initialize');
  let initResult = null;
  try {
    initResult = await Promise.race([
      request('initialize', initParams),
      sleep(20000).then(() => null),
    ]);
  } catch (e) { /* fall through to summary */ }
  if (initResult) {
    log('<-- initialize OK', JSON.stringify(initResult).slice(0, 300));
  } else {
    log('!! no initialize response within 20s');
  }

  log('--> session/new');
  const newResult = await Promise.race([
    request('session/new', { cwd: CWD, mcpServers: [] }),
    sleep(20000).then(() => null),
  ]);
  if (newResult && newResult.sessionId) {
    sessionId = newResult.sessionId;
    log('<-- session/new OK sessionId =', sessionId);
    // NOTE: the real client (1.18.33) returns NO `availableCommands` and NO
    // `modes`. It returns `configOptions` -- a list of typed selectors (model,
    // mode, ...) each with `currentValue` + `options`. Dump whatever keys are
    // actually present so the bridge can be built against reality, and guard
    // every .slice() because JSON.stringify(undefined) returns undefined.
    const keys = newResult && typeof newResult === 'object' ? Object.keys(newResult) : [];
    log('    session/new result keys =', JSON.stringify(keys));
    if (Array.isArray(newResult.availableCommands)) {
      log('    availableCommands:',
        JSON.stringify(newResult.availableCommands).slice(0, 600));
    } else {
      log('    availableCommands: (absent)');
    }
    if (Array.isArray(newResult.configOptions)) {
      log('    configOptions:');
      for (const opt of newResult.configOptions) {
        const cur = opt && opt.currentValue;
        const vals = (opt && Array.isArray(opt.options))
          ? opt.options.map((o) => o && o.value).filter(Boolean)
          : [];
        log('      -', opt && opt.category, '|', opt && opt.name,
            '| current =', cur, '|', vals.length, 'options:', vals.join(', '));
      }
    }
  } else {
    log('!! session/new failed:', brief(newResult, 400));
  }

  if (sessionId) {
    const promptBlocks = [{ type: 'text', text: PROMPT }];
    log('--> session/prompt');
    const result = await Promise.race([
      request('session/prompt', { sessionId, prompt: promptBlocks }),
      sleep(SECONDS * 1000).then(() => null),
    ]);
    if (result) {
      stopReason = result.stopReason || null;
      log('<-- session/prompt OK stopReason =', stopReason);
    } else {
      log('!! no session/prompt response within', SECONDS, 's');
    }
  }

  await sleep(1500); // let trailing notifications land
  turnEnded = true;

  summary();
  try { child.stdin.end(); } catch (_) { /* already gone */ }
  try { child.kill('SIGTERM'); } catch (_) { /* already gone */ }
  setTimeout(() => process.exit(0), 400);
})();

// ---------------------------------------------------------------- summary

function summary() {
  flush();
  log('');
  log('================ ACP SPIKE SUMMARY ================');
  log('sessionId      :', sessionId || '(none)');
  log('stopReason     :', stopReason || '(none)');
  log('turn ended     :', turnEnded);
  log('permissions    :', permissionCount);
  log('fs read/write  :', fsReadCount, '/', fsWriteCount);
  log('transcript     :', OUT, `(${rawLines.length} lines)`);
  log('');
  log('-- methods seen --');
  for (const [m, c] of [...methodsSeen].sort()) log('  ', m, 'x' + c);
  log('');
  log('-- session/update kinds (THE BRIDGE DEPENDS ON THIS) --');
  if (updateKinds.size === 0) log('   (none)');
  for (const [k, c] of [...updateKinds].sort()) log('  ', k, 'x' + c);
  log('');
  log('-- tool calls --');
  if (toolCalls.length === 0) log('   (none)');
  for (const t of toolCalls) {
    log('  ', t.kind, '| id=' + t.toolCallId, '| kind=' + t.kindField, '| status=' + t.status);
    log('      title =', JSON.stringify(t.title));
  }
  log('');
  log('Paste the transcript + this summary back to the app to finish the bridge.');
  log('====================================================');
}

process.on('uncaughtException', (e) => {
  log('!! uncaught:', e && e.stack ? e.stack : String(e));
  try { summary(); } catch (_) { /* ignore */ }
  process.exit(1);
});
process.on('SIGINT', () => { try { summary(); } catch (_) {} process.exit(130); });
