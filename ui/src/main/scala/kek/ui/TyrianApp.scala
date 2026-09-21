package kek.ui

import cats.effect.IO
import kek.dsl.*
import tyrian.*
import tyrian.Html.*
import tyrian.syntax.*
import scala.scalajs.js.annotation.*

enum Page:
  case Editor, Graph

final case class Model(source: String, result: Compiler.Result, page: Page = Page.Editor,
    graph: GraphState = GraphState())

enum Msg:
  case Edit(text: String)
  case LoadExample
  case Saved
  case NoOp
  case ShowPage(page: Page)
  case GraphFocus(node: Option[String])
  case GraphEdgeFocus(edge: Option[String])
  case GraphPath(start: Option[String])
  case GraphFilter(aggregate: Option[String])
  case GraphZoom(delta: Double)
  case GraphZoomAt(delta: Double, x: Double, y: Double)
  case GraphResetView
  case GraphDragStart(x: Double, y: Double)
  case GraphDragMove(x: Double, y: Double)
  case GraphDragEnd
  case Goto(word: String)
  case GotoLoc(line: Int, col: Int)

@JSExportTopLevel("TyrianApp")
object TyrianApp extends TyrianIOApp[Msg, Model]:

  def router: Location => Msg =
    Routing.none(Msg.NoOp)

  def init(flags: Map[String, String]): (Model, Cmd[IO, Msg]) =
    val source = Storage.load().getOrElse(Sample.source)
    val result = Compiler.compile(source)
    (Model(source, result),
      Cmd.Run(IO { EditorBridge.mount("editor-host", source, result.diagnostics); Msg.NoOp }))

  def update(model: Model): Msg => (Model, Cmd[IO, Msg]) =
    case Msg.Edit(text) =>
      val updated = model.copy(source = text, result = Compiler.compile(text))
      (updated,
        Cmd.Run(IO {
          Storage.save(text)
          EditorBridge.setDiagnostics(updated.result.diagnostics)
          Msg.NoOp
        }))
    case Msg.LoadExample =>
      val fresh = Model(Sample.source, Compiler.compile(Sample.source), model.page, model.graph)
      (fresh,
        Cmd.Run(IO {
          Storage.save(Sample.source)
          EditorBridge.setDoc(Sample.source)
          EditorBridge.setDiagnostics(fresh.result.diagnostics)
          Msg.NoOp
        }))
    case Msg.Saved | Msg.NoOp =>
      (model, Cmd.None)
    case Msg.ShowPage(page) =>
      val cmd = page match
        case Page.Editor =>
          Cmd.Run(IO { EditorBridge.attach("editor-host"); Msg.NoOp })
        case Page.Graph =>
          Cmd.Run(IO { GraphBridge.attach(); Msg.NoOp })
      (model.copy(page = page), cmd)
    // Graph pane events are one-line adapters: every invariant lives in GraphState's transitions.
    case Msg.GraphFocus(node) =>
      (model.copy(graph = model.graph.selectNode(node)), Cmd.None)
    case Msg.GraphEdgeFocus(edge) =>
      (model.copy(graph = model.graph.selectEdge(edge)), Cmd.None)
    case Msg.GraphPath(start) =>
      (model.copy(graph = model.graph.followPath(start)), Cmd.None)
    case Msg.GraphFilter(aggregate) =>
      (model.copy(graph = model.graph.filterBy(aggregate)), Cmd.None)
    case Msg.GraphZoom(delta) =>
      (model.copy(graph = model.graph.zoomBy(delta)), Cmd.None)
    case Msg.GraphZoomAt(delta, x, y) =>
      (model.copy(graph = model.graph.zoomAt(delta, x, y)), Cmd.None)
    case Msg.GraphResetView =>
      (model.copy(graph = model.graph.resetView), Cmd.None)
    case Msg.GraphDragStart(x, y) =>
      (model.copy(graph = model.graph.dragStart(x, y)), Cmd.None)
    case Msg.GraphDragMove(x, y) =>
      (model.copy(graph = model.graph.dragMove(x, y)), Cmd.None)
    case Msg.GraphDragEnd =>
      (model.copy(graph = model.graph.dragEnd), Cmd.None)
    case Msg.Goto(word) =>
      model.result.model.flatMap(_.declarations.find(_.name == word)) match
        case Some(d) if d.line > 0 => (model, Cmd.Run(IO { EditorBridge.goto(d.line, d.col); Msg.NoOp }))
        case _                     => (model, Cmd.None)
    case Msg.GotoLoc(line, col) =>
      (model, Cmd.Run(IO { EditorBridge.goto(line, col); Msg.NoOp }))

  def view(model: Model): Html[Msg] =
    val context = model.result.model
    div(id := "app")(
      header(cls := "topbar")(
        h1(cls := "brand")("kekdsl"),
        span(cls := "context")(context.map(_.name).getOrElse("—")),
        span(cls := s"status ${if model.result.hasErrors then "bad" else "good"}")(
          if model.result.hasErrors then s"${model.result.errors.size} error(s)"
          else "OK"
        ),
        button(onClick(Msg.LoadExample), cls := "btn")("Load example")
      ),
      nav(cls := "tabs")(
        tabButton(Page.Editor, "Editor", model.page),
        tabButton(Page.Graph, "Graph", model.page)
      ),
      model.page match
        case Page.Editor =>
          main(cls := "workbench")(
            editorPane(),
            inspectorPane(model)
          )
        case Page.Graph =>
          context match
            case Some(m) =>
              main(cls := "graph-main")(GraphView.pane(m, model.graph))
            case None =>
              main(cls := "workbench")(
                section(cls := "editor-pane")(
                  div(cls := "pane-title")("Fix the errors to see the graph"),
                  diagnosticsList(model.result.diagnostics)
                )
              )
    )

  def subscriptions(model: Model): Sub[IO, Msg] =
    Sub.Batch(
      Sub.make[IO, EditorBridge.Event, Msg, Unit]("editor-events")(
        dispatch => IO { EditorBridge.install(dispatch) }
      )(
        _ => IO.unit
      )(
        {
          case EditorBridge.Event.SourceChanged(text) => Some(Msg.Edit(text))
          case EditorBridge.Event.GotoWord(word)      => Some(Msg.Goto(word))
        }
      ),
      Sub.make[IO, GraphBridge.Event, Msg, Unit]("graph-events")(
        dispatch => IO { GraphBridge.install(dispatch) }
      )(
        _ => IO.unit
      )(
        { case GraphBridge.Event.Zoom(delta, x, y) => Some(Msg.GraphZoomAt(delta, x, y)) }
      )
    )

  private def tabButton(page: Page, label: String, active: Page): Html[Msg] =
    button(onClick(Msg.ShowPage(page)), cls := s"tab${if active == page then " active" else ""}")(label)

  private def editorPane(): Html[Msg] =
    section(cls := "editor-pane")(
      div(cls := "pane-title")("Context definition — double-click a word to jump to its declaration"),
      div(id := "editor-host", cls := "editor-host")()
    )

  private def inspectorPane(model: Model): Html[Msg] =
    section(cls := "inspector-pane")(
      div(cls := "pane-title")(
        if model.result.diagnostics.nonEmpty then "Diagnostics" else "Context"
      ),
      if model.result.diagnostics.nonEmpty then diagnosticsList(model.result.diagnostics)
      else summary(model.result.model.get)
    )

  private def diagnosticsList(diags: List[Diagnostic]): Html[Msg] =
    ul(cls := "diagnostics")(
      diags.map { d =>
        li(
          cls := s"diag ${if d.severity == Severity.Error then "error" else "warning"}",
          onClick(Msg.GotoLoc(d.span.line, d.span.col))
        )(
          span(cls := "loc")(s"Ln ${d.span.line}, Col ${d.span.col}"),
          code(d.message)
        )
      }*
    )

  private def summary(m: ContextModel): Html[Msg] =
    div(cls := "summary")(
      div(cls := "counts")(
        tile("values", m.count(DeclKind.ValueKind)),
        tile("entities", m.count(DeclKind.EntityKind)),
        tile("roots", m.count(DeclKind.AggregateRootKind)),
        tile("commands", m.count(DeclKind.CommandKind)),
        tile("events", m.count(DeclKind.DomainEventKind)),
        tile("integration", m.count(DeclKind.IntegrationEventKind)),
        tile("errors", m.count(DeclKind.ErrorKind)),
        tile("behavior", m.count(DeclKind.DomainEntityKind))
      ),
      group(m, DeclKind.ActorKind, "Actors"),
      group(m, DeclKind.AggregateRootKind, "Aggregate roots"),
      group(m, DeclKind.DomainEntityKind, "Domain entities"),
      group(m, DeclKind.CommandKind, "Commands"),
      group(m, DeclKind.DomainEventKind, "Domain events"),
      group(m, DeclKind.IntegrationEventKind, "Integration events"),
      group(m, DeclKind.ErrorKind, "Errors"),
      group(m, DeclKind.EntityKind, "Entities"),
      group(m, DeclKind.ValueKind, "Values")
    )

  private def tile(label: String, n: Int): Html[Msg] =
    div(cls := "tile")(
      span(cls := "n")(n.toString),
      span(cls := "l")(label)
    )

  private def group(m: ContextModel, kind: DeclKind, title: String): Html[Msg] =
    val items = m.byKind(kind)
    div(cls := "group")(
      h2(cls := "group-title")(s"$title (${items.size})") ::
        items.map(i =>
          div(
            cls := "decl",
            attr("title") := "Click to jump to the declaration",
            onClick(Msg.GotoLoc(i.line, i.col))
          )(
            code(i.name),
            i.underlying.fold(span(cls := "hidden")(""))(u => span(cls := "underlying")(s" ($u)")),
            i.description.fold(span(cls := "hidden")(""))(d => span(cls := "desc")(d))
          )
        )*
    )
