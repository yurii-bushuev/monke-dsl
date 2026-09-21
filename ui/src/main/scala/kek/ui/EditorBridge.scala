package kek.ui

import kek.dsl.Diagnostic
import scala.scalajs.js

/** The Tyrian↔CodeMirror seam. All CodeMirror knowledge lives in ui/editor.js (bundled into
  * ui/vendor/editor.js); this bridge adapts its imperative API and callbacks into the Elm loop.
  */
object EditorBridge:

  sealed trait Event
  object Event:
    final case class SourceChanged(text: String) extends Event
    final case class GotoWord(word: String)      extends Event

  @volatile private var dispatch: Option[Either[Throwable, Event] => Unit] = None

  /** Called from the subscription's acquire: captures the Tyrian dispatch and installs the JS hooks. */
  def install(d: Either[Throwable, Event] => Unit): Unit =
    dispatch = Some(d)

  /** Mounts the editor once the host element exists (the view may render a frame later than init). */
  def mount(hostId: String, source: String, diagnostics: List[Diagnostic]): Unit =
    if !tryMount(hostId, source, diagnostics) then
      var attempts = 0
      def step(): Unit =
        attempts += 1
        if !tryMount(hostId, source, diagnostics) && attempts < 120 then raf(() => step())
      raf(() => step())

  /** Re-attaches the mounted view after the virtual DOM replaced its host (e.g. a tab switch). */
  def attach(hostId: String): Unit =
    if !tryAttach(hostId) then
      var attempts = 0
      def step(): Unit =
        attempts += 1
        if !tryAttach(hostId) && attempts < 120 then raf(() => step())
      raf(() => step())

  private def tryAttach(hostId: String): Boolean = api match
    case Some(a) => a.attach(hostId).asInstanceOf[Boolean]
    case None    => false

  private def raf(f: () => Unit): Unit =
    js.Dynamic.global.requestAnimationFrame({ () => f() }: js.Function0[Unit])

  private def tryMount(hostId: String, source: String, diagnostics: List[Diagnostic]): Boolean =
    api match
      case Some(a) =>
        val ok = a.mount(hostId, source, hooksLiteral).asInstanceOf[Boolean]
        if ok then setDiagnostics(diagnostics)
        ok
      case None => false

  private def hooksLiteral: js.Dynamic =
    val onChanged: js.Function1[String, Unit] = (text: String) =>
      dispatch.foreach(_(Right(Event.SourceChanged(text))))
    val onGoto: js.Function1[String, Unit] = (word: String) =>
      dispatch.foreach(_(Right(Event.GotoWord(word))))
    js.Dynamic.literal("onChanged" -> onChanged, "onGoto" -> onGoto)

  private def api: Option[js.Dynamic] =
    val a = js.Dynamic.global.kekEditor
    if js.isUndefined(a) || a == null then None else Some(a)

  def setDiagnostics(diagnostics: List[Diagnostic]): Unit = api.foreach { a =>
    val list = diagnostics.map { d =>
      js.Dynamic.literal(
        "from" -> d.span.start,
        "to" -> math.max(d.span.start + 1, d.span.end)
      )
    }
    a.setDiagnostics(js.Array(list*))
  }

  def setDoc(source: String): Unit = api.foreach(_.setDoc(source))

  def goto(line: Int, col: Int): Unit = api.foreach(_.goto(line, col))
