# kekdsl

A Scala 3 compiler for the **Domain Boundary DSL** — a small language for describing the domain model
of a DDD bounded context: values, entities and aggregate roots, commands, domain events, integration
events, domain errors, command handlers, and state transitions.

The compiled context is explored in a browser workbench (Scala.js + [Tyrian](https://tyrian.indigoengine.io)):
edit the DSL with syntax highlighting and error markers, then read the model as an interactive graph of
actors, commands, aggregates, events, and errors.

[`PROPOSAL.md`](PROPOSAL.md) is the language specification and the single source of truth.

## Build

Prerequisites: JDK 17+, [sbt 2](https://www.scala-sbt.org) (`sbt-launch`), Node.js 20+ (UI tooling).

```bash
# compile the compiler and run its test suite
sbt dslJVM/test

# build the workbench JavaScript (writes ui/linked/main.js)
sbt ui/fastLinkJS

# serve the workbench with hot reload
cd ui && npm install && npm run start
# open http://localhost:1234
```

The editor's highlighting bundle (`ui/vendor/editor.js`) is committed — rebuild it with
`npm run build:editor` only after changing `ui/editor.js`.
