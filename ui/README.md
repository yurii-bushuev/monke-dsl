# kekdsl-ui

Browser workbench for the Domain Boundary DSL, built with [Tyrian](https://tyrian.indigoengine.io/) (Elm-style architecture) on Scala.js. It compiles the `kek.dsl` compiler in the browser: edit the bounded-context definition on the left, get live diagnostics (or a model summary) on the right. The edited source is persisted to `localStorage`; the §22 example is preloaded on first run.

The **Graph** tab renders the compiled context as a layered flow (Commands → Aggregate Roots → Domain Events → Integration Events): click a node to focus it (incident edges stay lit, the rest dim, a details panel shows the declaration and its connections), drag to pan, zoom with the toolbar buttons.

## The editor

The editor is CodeMirror 6 with DSL syntax highlighting (keywords, annotations, strings, comments), line numbers, and undo history. Errors from the live compile appear as wavy underlines at their exact source positions; **double-click any identifier to jump to its declaration** (the target flashes and scrolls to center), and clicking a diagnostic in the right pane jumps to its location.

All CodeMirror knowledge lives in `editor.js`, which is prebuilt into the committed bundle `vendor/editor.js` (`npm run build:editor` in `ui/` — only needed when `editor.js` itself changes). The daily loop stays sbt-only: `sbt ~ui/fastLinkJS` and reload.

## Prerequisites

- Node.js + npm — required for the Parcel dev server and for running the Scala.js tests (`sbt ui/test` executes under Node)
- sbt

## Development workflow

Use two terminal tabs.

Terminal 1 — build the Scala.js app (add `~` for watch mode):

```sh
sbt ui/fastLinkJS
```

Terminal 2 — from this `ui/` directory, serve the app:

```sh
npm install    # first time only
npm run start  # http://localhost:1234
```

Parcel watches the output of `fastLinkJS` (loaded via `tyrianapp.js`), so every re-link hot-reloads in the browser.

## Production build

```sh
sbt ui/fullLinkJS
npm run build
```

Note: the linked module is written straight into `ui/linked/` (`fastLinkJS`) and `ui/linked-opt/` (`fullLinkJS`) — see `Compile / fastLinkJS / scalaJSLinkerOutputDirectory` in `build.sbt`. `tyrianapp.js` imports `./linked/main.js` relatively, which works under both the Parcel dev server and IntelliJ's built-in server (which cannot serve files from `target/`). For production, point the import at `./linked-opt/main.js` or keep dev and prod entry files separate.

## Layout

- `index.html` — entry page; the app mounts into `<div id="myapp">`
- `tyrianapp.js` — imports the Scala.js module and launches `TyrianApp`
- `styles.css` — workbench layout (editor + inspector panes)
- `src/main/scala/kek/ui/` — the Tyrian workbench (`Model` / `Msg` / `update` / `view`) and `localStorage` persistence
