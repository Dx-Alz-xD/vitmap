// Runs code for the Code Writer inside a WebView. Output goes to the VitmapHost bridge.
(function () {
  "use strict";
  const host = window.VitmapHost;
  const show = (v) => {
    if (typeof v === "string") return v;
    if (v instanceof Error) return v.stack || String(v);
    try { return JSON.stringify(v, null, 0) ?? String(v); } catch (e) { return String(v); }
  };
  const say = (kind) => (...args) => host.write(kind, args.map(show).join(" ") + "\n");
  console.log = say("out");
  console.info = say("out");
  console.debug = say("out");
  console.warn = say("err");
  console.error = say("err");

  // Programs read input with readline()/input(); prompt() works too (Tau Prolog uses it).
  window.readline = () => host.readLine();
  window.input = (msg) => { if (msg) host.write("out", String(msg)); return host.readLine(); };
  window.prompt = (msg) => { if (msg) host.write("out", String(msg)); const l = host.readLine(); return l === null ? "" : l; };
  window.alert = (msg) => host.write("out", String(msg) + "\n");

  let finished = false;
  const finish = (code) => { if (!finished) { finished = true; host.done(code); } };
  window.addEventListener("error", (e) => { host.write("err", (e.error && e.error.stack) || String(e.message) + "\n"); finish(1); });
  window.addEventListener("unhandledrejection", (e) => { host.write("err", show(e.reason) + "\n"); finish(1); });

  const load = (src) => new Promise((resolve, reject) => {
    const s = document.createElement("script");
    s.src = src;
    s.onload = resolve;
    s.onerror = () => reject(new Error("Couldn't load " + src + ". Is the runtime downloaded?"));
    document.head.appendChild(s);
  });
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  const runJs = async (code) => { await new AsyncFunction("exports", "module", code)({}, { exports: {} }); };

  async function typescript(code) {
    if (!window.ts) await load("/rt/code-ts/typescript.js");
    const r = ts.transpileModule(code, {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, strict: true },
      reportDiagnostics: true,
      fileName: "main.ts",
    });
    for (const d of r.diagnostics || []) {
      const at = d.file && d.start != null ? d.file.getLineAndCharacterOfPosition(d.start) : null;
      host.write("err", (at ? `main.ts:${at.line + 1}:${at.character + 1} ` : "") + ts.flattenDiagnosticMessageText(d.messageText, "\n") + "\n");
    }
    await runJs(r.outputText);
  }

  async function coffee(code) {
    if (!window.CoffeeScript) await load("/rt/code-coffee/coffeescript.js");
    await runJs(CoffeeScript.compile(code, { bare: true }));
  }

  async function scheme(code) {
    if (!window.BiwaScheme) await load("/rt/code-scheme/biwascheme-min.js");
    await new Promise((resolve, reject) => {
      BiwaScheme.Port.current_output = new BiwaScheme.Port.CustomOutput((s) => host.write("out", s));
      const interp = new BiwaScheme.Interpreter((e) => reject(new Error((e && e.message) || String(e))));
      interp.evaluate(code, (result) => {
        if (result !== undefined && result !== BiwaScheme.undef && result !== null) host.write("out", "=> " + BiwaScheme.to_write(result) + "\n");
        resolve();
      });
    });
  }

  // Runs ":- initialization(G)." goals and "?- Query." lines after consulting the rest.
  async function prolog(code) {
    if (!window.pl) {
      await load("/rt/code-prolog/core.js");
      await load("/rt/code-prolog/lists.js");
      await load("/rt/code-prolog/format.js");
    }
    const goals = [];
    const program = [];
    let pending = null;
    for (const line of code.split("\n")) {
      const t = line.trim();
      if (pending !== null) {
        pending.text += " " + t;
        if (t.endsWith(".")) { goals.push(pending); pending = null; }
        continue;
      }
      const init = t.match(/^:-\s*initialization\((.*)\)\s*\.$/);
      if (init) { goals.push({ text: init[1] + ".", query: false }); continue; }
      if (t.startsWith("?-")) {
        const g = { text: t.slice(2).trim(), query: true };
        if (g.text.endsWith(".")) goals.push(g); else pending = g;
        continue;
      }
      program.push(line);
    }
    const session = pl.create(1000000);
    const out = new pl.type.Stream({ put: (text) => { host.write("out", text); return true; }, flush: () => true }, "append", "user_output", "text", false, "reset");
    session.streams.user_output = out;
    session.standard_output = out;
    session.current_output = out;
    await new Promise((resolve, reject) => session.consult(":- use_module(library(lists)).\n:- use_module(library(format)).\n" + program.join("\n"), {
      success: resolve,
      error: (e) => reject(new Error(pl.format_answer(e, session))),
    }));
    for (const g of goals) {
      if (g.query) host.write("out", "?- " + g.text + "\n");
      await new Promise((resolve, reject) => session.query(g.text, { success: resolve, error: (e) => reject(new Error(pl.format_answer(e, session))) }));
      let shown = 0;
      await new Promise((resolve) => {
        const next = () => session.answer({
          success: (a) => {
            if (g.query) host.write("out", pl.format_answer(a, session) + "\n");
            shown++;
            if (g.query && shown < 25) next(); else resolve();
          },
          fail: () => { if (g.query) host.write("out", shown ? "" : "false.\n"); resolve(); },
          error: (e) => { host.write("err", pl.format_answer(e, session) + "\n"); resolve(); },
          limit: () => { host.write("err", "Stopped: inference limit reached\n"); resolve(); },
        });
        next();
      });
    }
  }

  async function ruby(code, stdin) {
    if (!window["ruby-wasm-wasi"]) await load("/rt/code-ruby/browser.umd.js");
    if (!window.__rubyModule) {
      host.write("info", "Starting Ruby…\n");
      const buf = await (await fetch("/rt/code-ruby/ruby+stdlib.wasm")).arrayBuffer();
      window.__rubyModule = await WebAssembly.compile(buf);
    }
    const { DefaultRubyVM } = window["ruby-wasm-wasi"];
    const { vm } = await DefaultRubyVM(window.__rubyModule, { consolePrint: true });
    const input = JSON.stringify(stdin).replace(/#/g, "\\#");
    vm.eval(`require "stringio"\n$stdin = StringIO.new(${input})\n`);
    vm.eval(code);
  }

  async function clojure(code) {
    if (!window.scittle) await load("/rt/code-clojure/scittle.js");
    const r = scittle.core.eval_string(code);
    if (r !== undefined && r !== null) host.write("out", "=> " + String(r) + "\n");
  }

  window.vitmapRun = async function (kind, code, stdin) {
    finished = false;
    try {
      switch (kind) {
        case "js": await runJs(code); break;
        case "ts": await typescript(code); break;
        case "coffee": await coffee(code); break;
        case "scheme": await scheme(code); break;
        case "prolog": await prolog(code); break;
        case "ruby": await ruby(code, stdin); break;
        case "clojure": await clojure(code); break;
        default: throw new Error("Unknown runtime " + kind);
      }
      finish(0);
    } catch (e) {
      host.write("err", show(e) + "\n");
      finish(1);
    }
  };
  host.ready();
})();
