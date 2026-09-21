// kekdsl editor: CodeMirror 6 wrapper.
//
// This is the ONLY file that knows about CodeMirror. It is bundled once into
// vendor/editor.js (`npm run build:editor`) and loaded as a plain ES module,
// so the page works under any static server — no bundler in the daily loop.
//
// Exposes window.kekEditor = { mount, setDoc, setDiagnostics, goto }.

import { EditorView, Decoration } from "@codemirror/view";
import { EditorState, StateEffect, StateField } from "@codemirror/state";
import { StreamLanguage, HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags as t } from "@lezer/highlight";
import { oneDark } from "@codemirror/theme-one-dark";
import { basicSetup } from "codemirror";

// IDEA-style yellow for @annotations (IntelliJ Darcula metadata color).
const kekOverrides = HighlightStyle.define([
  { tag: t.meta, color: "#bbb529" }
]);

const KEYWORDS = new Set([
  "bounded-context", "value", "entity", "command", "event", "integration-event",
  "error", "domain-entity", "on", "handles", "transition", "from", "emits",
  "actor", "triggers", "fails", "match", "case"
]);

// Identifiers may contain hyphens, but a hyphen only continues the word when
// followed by a letter or underscore, so `A -> B` never lexes as one token.
const IDENT = /[A-Za-z_][A-Za-z0-9_]*(?:-[A-Za-z_][A-Za-z0-9_]*)*/;

const kekLanguage = StreamLanguage.define({
  startState: () => ({ inBlock: false }),
  token(stream, state) {
    if (state.inBlock) {
      while (!stream.eol()) {
        if (stream.match("*/")) { state.inBlock = false; break; }
        stream.next();
      }
      return "comment";
    }
    if (stream.eatSpace()) return null;
    if (stream.match("//")) { stream.skipToEnd(); return "comment"; }
    if (stream.match("/*")) {
      state.inBlock = true;
      while (!stream.eol()) {
        if (stream.match("*/")) { state.inBlock = false; break; }
        stream.next();
      }
      return "comment";
    }
    if (stream.match('"')) {
      let escaped = false;
      while (!stream.eol()) {
        const ch = stream.next();
        if (escaped) escaped = false;
        else if (ch === "\\") escaped = true;
        else if (ch === '"') break;
      }
      return "string";
    }
    if (stream.match("@")) {
      if (stream.match(/[A-Za-z0-9_-]+/)) return "meta";
      return null;
    }
    if (stream.match("->")) return "operator";
    if (stream.match("=")) return "operator";
    if (stream.match(/[{}();:,]/)) return "punctuation";
    if (stream.match(IDENT)) {
      return KEYWORDS.has(stream.current()) ? "keyword" : null;
    }
    stream.next();
    return null;
  }
});

const setDiags = StateEffect.define();

const diagField = StateField.define({
  create: () => Decoration.none,
  update(value, tr) {
    value = value.map(tr.changes);
    for (const e of tr.effects) {
      if (e.is(setDiags)) value = e.value;
    }
    return value;
  },
  provide: (f) => EditorView.decorations.from(f)
});

const addFlash = StateEffect.define();
const clearFlash = StateEffect.define();

const flashField = StateField.define({
  create: () => Decoration.none,
  update(value, tr) {
    for (const e of tr.effects) {
      if (e.is(addFlash)) {
        return Decoration.set([
          Decoration.mark({ class: "kek-goto-flash" }).range(e.value.from, e.value.to)
        ]);
      }
      if (e.is(clearFlash)) return Decoration.none;
    }
    return value;
  },
  provide: (f) => EditorView.decorations.from(f)
});

let view = null;

function attachTo(host) {
  if (view.dom.parentNode !== host) host.appendChild(view.dom);
  view.requestMeasure();
}

function clampPos(pos) {
  return Math.max(0, Math.min(pos, view.state.doc.length));
}

function buildExtensions(hooks) {
  return [
    basicSetup,
    kekLanguage,
    oneDark,
    syntaxHighlighting(kekOverrides),
    diagField,
    flashField,
    EditorView.updateListener.of((u) => {
      if (u.docChanged && hooks && hooks.onChanged) hooks.onChanged(u.state.doc.toString());
    }),
    EditorView.domEventHandlers({
      dblclick(event, v) {
        const pos = v.posAtCoords({ x: event.clientX, y: event.clientY });
        if (pos == null) return false;
        const word = v.state.wordAt(pos);
        if (word && !word.empty && hooks && hooks.onGoto) {
          hooks.onGoto(v.state.sliceDoc(word.from, word.to));
        }
        return false;
      }
    })
  ];
}

window.kekEditor = {
  mount(hostId, doc, hooks) {
    const host = document.getElementById(hostId);
    if (!host) return false;
    // The virtual DOM may have replaced the host (e.g. a tab switch destroyed it) — re-attach the
    // surviving view instead of dropping it.
    if (view) { attachTo(host); return true; }
    view = new EditorView({
      state: EditorState.create({ doc: doc, extensions: buildExtensions(hooks) }),
      parent: host
    });
    return true;
  },

  attach(hostId) {
    const host = document.getElementById(hostId);
    if (!host || !view) return false;
    attachTo(host);
    return true;
  },

  setDoc(text) {
    if (!view) return;
    view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: text } });
  },

  setDiagnostics(list) {
    if (!view) return;
    const docLen = view.state.doc.length;
    const marks = (list || [])
      .filter((d) => d.to > d.from)
      .map((d) =>
        Decoration.mark({ class: "kek-error" }).range(
          Math.max(0, Math.min(d.from, docLen)),
          Math.max(1, Math.min(d.to, docLen))
        )
      )
      .sort((a, b) => a.from - b.from);
    view.dispatch({ effects: setDiags.of(Decoration.set(marks)) });
  },

  goto(line, col) {
    if (!view) return;
    const lines = view.state.doc.lines;
    const lineNo = Math.min(Math.max(1, line), lines);
    const lineInfo = view.state.doc.line(lineNo);
    const pos = Math.min(lineInfo.from + Math.max(0, col - 1), lineInfo.to);
    const to = Math.min(pos + 40, lineInfo.to);
    view.dispatch({
      selection: { anchor: pos },
      effects: [
        EditorView.scrollIntoView(pos, { y: "center" }),
        addFlash.of({ from: pos, to: Math.max(to, pos + 1) })
      ]
    });
    setTimeout(() => {
      if (view) view.dispatch({ effects: clearFlash.of(null) });
    }, 600);
  }
};
