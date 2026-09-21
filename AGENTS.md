# AGENTS.md

Instructions for AI coding agents working in this repository.

## Project Overview

**kekdsl** is a Scala 3 compiler project for the **Domain Boundary DSL** — a language that describes the domain model and behavior of a single DDD Bounded Context: values, entities and aggregate roots, commands, domain events, integration events, domain errors, command handlers, and domain transitions.

**`PROPOSAL.md` at the repo root is the language specification and the single source of truth.** It contains the full EBNF grammar (§2–§24), a complete example (§22), the V1 semantic rules the compiler must enforce (§23), and the list of features deliberately out of scope (§25). Read the relevant sections before implementing or changing anything language-related.

Status: greenfield. `src/main/scala` and `src/test/scala` exist but contain no source yet.

## Toolchain

- sbt **2.0.9** (pinned in `project/build.properties`)
- Scala **3.9.0** (set in `build.sbt`)
- Test framework: **munit 1.3.6**
- sbt plugin: `sbt-ide-settings` (JetBrains) in `project/plugins.sbt`
- Scala.js plugin: `sbt-scalajs` **1.22.0** (first line with sbt 2.x support)
- UI framework: **Tyrian 0.14.0** (`tyrian-io`, Cats Effect `IO`) in the `ui/` module
- Browser tooling: **Node.js + npm** with **Parcel** (`ui/package.json`) — required to serve the UI and to run `sbt ui/test` (Scala.js tests execute under Node)
- Development environment: Windows; run sbt commands through Git Bash (`sbt` resolves to `sbt.bat`)


## Commands

IMPORTANT: When applicable, prefer using intellij-index MCP tools for code navigation and refactoring.

- `sbt compile` — compile main sources
- `sbt test` — run the root project's munit suite
- `sbt dslJVM/test` — run the compiler's munit suite (lexer/parser/§23 checks, JVM)
- `sbt console` — Scala REPL with the project classpath
- `sbt clean` — remove build outputs
- `sbt ui/fastLinkJS` — dev build of the UI JavaScript (`ui/tyrianapp.js` expects this output); prefix with `~` to watch
- `sbt ui/fullLinkJS` — optimised production JavaScript
- `sbt ui/test` — **currently broken upstream**: `sbt-scalajs_sbt2_3` 1.22.0 ships `scalajs-env-nodejs` 1.6.0 (the last `_3` publication), whose stdin/REPL launch cannot host the 1.22 test bridge's RPC — every run dies with `RunTerminatedException` before any test executes. The GraphState tests in `ui/src/test` compile and are linked into the test bundle; run them once a working `_3` env is published (or via sbt 1). Do not sink time into re-diagnosing.
- `npm run start` (from `ui/`, after `npm install`) — Parcel dev server at http://localhost:1234 with hot reload
- `npm run build:editor` (from `ui/`) — rebuild the committed `ui/vendor/editor.js` bundle (CodeMirror wrapper); only needed when `ui/editor.js` changes

## Conventions

- Package prefix: **`kek`** (configured via `idePackagePrefix` in `build.sbt`) — place sources under `src/main/scala/kek/...` and tests under `src/test/scala/kek/...`.
- Standard sbt layout: `src/main/scala`, `src/test/scala`. The root project is a placeholder for future JVM tooling. The compiler core lives in `dsl/` (cross-built for JVM + Scala.js via `crossProject`, sources under `dsl/shared/src/main/scala/kek/dsl/...`, tests under `dsl/shared/src/test/...`); `ui/` holds the Scala.js/Tyrian workbench with sources under `ui/src/main/scala/kek/ui/...` and depends on `dslJS`. The root does **not** aggregate subprojects — use `sbt <module>/<task>` explicitly.
- Scala.js dependencies use plain `%%` (sbt 2 resolves the platform suffix); do not use `%%%`.
- Library dependencies are added to `libraryDependencies` in `build.sbt`; test dependencies use the `% Test` classifier.

## Language Ground Rules (from PROPOSAL.md)

- A source file contains exactly one `bounded-context`; declarations are `value`, `entity`, `command`, `event`, `integration-event`, `error`, `domain-entity`, and `actor`.
- A handler's decide stage is either a single `on S -> E [fails E1, ...]` or a branched `match S { case "<condition>" -> E | case "<condition>" -> fails E1, ... }` (§13); conditions are documentary strings — the DSL has no expression language, and §23.7/§23.18 apply per arm.
- The compiler must enforce the semantic rules in §23: unique declaration names, resolvable type references, `@aggregate-root` only on entities, `domain-entity ... on <type>` must target an aggregate root, handler and transition references must resolve, a handler's `from` clause must reference an integration event, a `triggers` clause must reference a command (commands are triggered only by an Actor or an Integration Event), every reference in a `fails` clause must be an error, transition output state must satisfy `S' <: S`, every type in an `emits` clause must be an integration event, an underlying clause must name an implementation type from the §18 vocabulary.
- There are no primitive, collection, optional, or implementation types in the DSL type system (§18, §24). The single exception: an atomic Value may declare its underlying implementation type (`value PaymentId = UUID;`) from the closed vocabulary in §18 — an opaque-type declaration, never usable as a type reference.
- Metadata annotations (`@description`, `@deprecated`, `@external-link`, `@tags`) are documentary only; `@aggregate-root` is the only annotation with domain semantics.
- Persistence, transports, serialization, and executable business logic are outside the language — keep them out of the compiler's scope.

## Notes

- The repository has no commits yet; `.gitignore` is a generic multi-IDE template (covers `target/`, `.bsp/`, IDE files).
- `.idea/` project files are partially tracked; avoid editing generated IDE files by hand.

## Agent skills

### Issue tracker

Issues are tracked as local markdown files under `.scratch/<feature>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default canonical triage labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.
