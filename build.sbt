ThisBuild / scalaVersion := "3.9.0"

lazy val root = (project in file("."))
  .settings(
    name := "kekdsl",
    idePackagePrefix := Some("kek"),
    libraryDependencies ++= Seq(
      //You can add library dependencies here, for example,
      //"org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.scalameta" %% "munit" % "1.3.6" % Test
    )
  )

// The kekdsl compiler for the Domain Boundary DSL (see PROPOSAL.md).
// Pure Scala, cross-built for JVM (tests, future CLI) and Scala.js (the ui workbench).
lazy val dsl = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("dsl"))
  .settings(
    name := "kekdsl-dsl",
    idePackagePrefix := Some("kek"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test,
    testFrameworks += new TestFramework("munit.Framework")
  )

lazy val dslJVM = dsl.jvm
lazy val dslJS  = dsl.js

// Browser workbench for editing and compiling the DSL, built with Tyrian (Elm-style) on Scala.js.
// Served via Parcel: see ui/README.md for the development workflow.
lazy val ui = (project in file("ui"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(dslJS)
  .settings(
    name := "kekdsl-ui",
    idePackagePrefix := Some("kek"),
    libraryDependencies ++= Seq(
      "io.indigoengine" %% "tyrian-io" % "0.14.0",
      "org.scalameta"   %% "munit"     % "1.3.6" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    // Emit a native ES module so the browser can `import { TyrianApp }` from main.js directly,
    // without Parcel having to transform CommonJS output.
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    // Link straight into ui/linked/ so any static server can serve it (IntelliJ's built-in server
    // can't reach target/ — excluded content). fullLinkJS goes to ui/linked-opt/.
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "linked",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "linked-opt"
  )
