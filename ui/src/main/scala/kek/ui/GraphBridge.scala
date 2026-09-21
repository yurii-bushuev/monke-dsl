package kek
package ui

import org.scalajs.dom
import scala.scalajs.js

/** Bridges native wheel and double-click events on the graph SVG into the Elm loop. Tyrian's event
  * attributes have no event-taking handlers for `wheel`/`dblclick` (only the drag family does), but
  * zoom-at-pointer needs both the event and its coordinates — so this seam attaches the two
  * listeners imperatively, following the EditorBridge pattern.
  */
object GraphBridge:

  sealed trait Event
  object Event:
    final case class Zoom(delta: Double, x: Double, y: Double) extends Event

  @volatile private var dispatch: Option[Either[Throwable, Event] => Unit] = None
  private var attachedTo: Option[dom.Element]                              = None

  /** Called from the subscription's acquire: captures the Tyrian dispatcher. */
  def install(d: Either[Throwable, Event] => Unit): Unit = dispatch = Some(d)

  /** Attaches the listeners once the graph SVG exists (the view renders a frame after the
    * tab-switch message). Re-attaches when the virtual DOM has replaced the element.
    */
  def attach(): Unit =
    if !tryAttach() then
      var attempts = 0
      def step(): Unit =
        attempts += 1
        if !tryAttach() && attempts < 120 then raf(() => step())
      raf(() => step())

  private def tryAttach(): Boolean =
    dom.document.querySelector(".graph-svg") match
      case el: dom.Element if !attachedTo.exists(_ eq el) =>
        detach()
        attachedTo = Some(el)
        el.addEventListener("wheel", onWheel)
        el.addEventListener("dblclick", onDblClick)
        true
      case _ =>
        attachedTo.isDefined // already wired to the live element

  private def detach(): Unit = attachedTo.foreach { el =>
    el.removeEventListener("wheel", onWheel)
    el.removeEventListener("dblclick", onDblClick)
  }

  private val onWheel: js.Function1[dom.Event, Unit] = e =>
    val we = e.asInstanceOf[dom.WheelEvent]
    we.preventDefault() // zoom the pane, don't scroll the page
    (attachedTo, dispatch) match
      case (Some(el), Some(d)) =>
        val (x, y) = canvasPos(el, we)
        d(Right(Event.Zoom(-we.deltaY * 0.0015, x, y)))
      case _ => ()

  /** Double-click on empty canvas zooms in one step at the pointer; hits on nodes and links are
    * left to their own Tyrian handlers (path highlight).
    */
  private val onDblClick: js.Function1[dom.Event, Unit] = e =>
    val me  = e.asInstanceOf[dom.MouseEvent]
    val hit = me.target.asInstanceOf[dom.Element].closest(".node, .link")
    if hit == null then
      (attachedTo, dispatch) match
        case (Some(el), Some(d)) =>
          val (x, y) = canvasPos(el, me)
          d(Right(Event.Zoom(0.3, x, y)))
        case _ => ()

  private def canvasPos(el: dom.Element, me: dom.MouseEvent): (Double, Double) =
    val rect = el.getBoundingClientRect()
    (me.clientX - rect.left, me.clientY - rect.top)

  private def raf(f: () => Unit): Unit =
    js.Dynamic.global.requestAnimationFrame({ () => f() }: js.Function0[Unit])
