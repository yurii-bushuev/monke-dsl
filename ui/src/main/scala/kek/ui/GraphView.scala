package kek.ui

import kek.dsl.{ContextModel, DeclInfo, DeclKind}
import tyrian.*
import tyrian.Html.*
import tyrian.SVG
import tyrian.syntax.*

/** Renders the context graph: layered columns, bezier links, click-to-focus, drag-pan and zoom. */
object GraphView:

  def pane(m: ContextModel, g: GraphState): Html[Msg] =
    val layout = GraphLayout(m)

    // Aggregate quick filter: keep the links owned by the selected aggregate and the nodes they
    // touch — the full connected world, not just adjacent nodes.
    val visibleLinks = g.filter match
      case Some(agg) => layout.links.filter(_.aggs.contains(agg))
      case None      => layout.links
    val visibleIds     = visibleLinks.flatMap(l => List(l.fromId, l.toId)).toSet
    val visibleNodes   = layout.nodes.filter(n => visibleIds.contains(n.id))
    val visibleColumnX = visibleNodes.map(_.x).toSet
    val visibleColumns = layout.columns.filter(c => visibleColumnX.contains(c.x))

    val near     = g.focus.fold(Set.empty[String])(GraphState.adjacency(visibleLinks, _))
    val pathSel  = g.path.map(s => (s, GraphState.reachableFrom(visibleLinks, s)))
    val children =
      EdgeKind.values.map(arrowMarker).toList :::
        background(layout) ::
        visibleColumns.map(columnHeader) :::
        visibleLinks.map(link(_, g.focus, g.edgeFocus, pathSel)) :::
        visibleNodes.map(node(_, g.focus, near, pathSel))
    div(cls := "graph-pane")(
      toolbar(g),
      filterBar(m, g),
      div(cls := "graph-canvas")(
        svg(
          cls := "graph-svg",
          onMouseDown((e: Tyrian.MouseEvent) => Msg.GraphDragStart(e.clientX, e.clientY)).usePreventDefault,
          onMouseMove((e: Tyrian.MouseEvent) => Msg.GraphDragMove(e.clientX, e.clientY)),
          onMouseUp(_ => Msg.GraphDragEnd),
          onMouseLeave(_ => Msg.GraphDragEnd)
          // wheel zoom and double-click zoom are wired imperatively by GraphBridge: Tyrian's event
          // attributes have no event-taking handlers for wheel/dblclick, and zoom-at-pointer needs
          // both the event and its coordinates.
        )(
          SVG.g(attr("transform") := s"translate(${g.panX}, ${g.panY}) scale(${g.zoom})")(children)
        )
      ),
      selectionPanel(m, visibleLinks, g)
    )

  private def selectionPanel(m: ContextModel, links: List[GraphLink], g: GraphState): Html[Msg] =
    g.focus.flatMap(details(m, links, _))
      .orElse(g.edgeFocus.flatMap(id => links.find(_.id == id)).map(edgeDetails))
      .getOrElse(div(cls := "graph-details empty")(""))

  private def edgeDetails(l: GraphLink): Html[Msg] =
    div(cls := "graph-details")(
      div(cls := "details-head")(
        span(cls := s"conn-kind ${l.kind.css}")(l.kind.css),
        code(s"${l.fromId} → ${l.toId}"),
        button(onClick(Msg.GraphEdgeFocus(None)), cls := "btn small")("✕")
      ) ::
        Option.when(l.name.nonEmpty)(
          div(cls := "desc")(span("method "), code(l.name))
        ).orEmpty ::
        l.cases.map(c => div(cls := "desc")(span("case "), code(s""""$c""""))) :::
        List(div(cls := "desc")(l.kind.legend)) *
    )

  private def filterBar(m: ContextModel, g: GraphState): Html[Msg] =
    val roots = m.byKind(DeclKind.AggregateRootKind).map(_.name).sorted
    if roots.length < 2 then div(cls := "graph-filters hidden")("")
    else
      div(cls := "graph-filters")(
        span(cls := "filters-label")("Aggregate:") ::
          filterChip(None, "All", g) ::
          roots.map(r => filterChip(Some(r), r, g)) *
      )

  private def filterChip(value: Option[String], label: String, g: GraphState): Html[Msg] =
    button(
      onClick(Msg.GraphFilter(value)),
      cls := s"filter-chip${if g.filter == value then " active" else ""}"
    )(label)

  private def toolbar(g: GraphState): Html[Msg] =
    div(cls := "graph-toolbar")(
      button(onClick(Msg.GraphZoom(-0.15)), cls := "btn small")("−"),
      span(cls := "zoom-label")(f"${g.zoom * 100}%.0f%%"),
      button(onClick(Msg.GraphZoom(0.15)), cls := "btn small")("+"),
      button(onClick(Msg.GraphResetView), cls := "btn small")("Reset view"),
      span(cls := "graph-hint")(
        "drag to pan · click node/edge to inspect · double-click for full path · wheel or double-click empty canvas to zoom"
      ),
      span(cls := "legend")(
        span(cls := "chip sends")(""),
        span("actor sends"),
        span(cls := "chip handles")(""),
        span("command → event"),
        span(cls := "chip emits")(""),
        span("emits"),
        span(cls := "chip triggered")(""),
        span("triggered by"),
        span(cls := "chip fails")(""),
        span("fails")
      ),
      g.focus.map { id =>
        button(onClick(Msg.GraphFocus(None)), cls := "btn small danger")(s"✕ $id")
      }.orEmpty
    )

  private def arrowMarker(kind: EdgeKind): Html[Msg] =
    SVG.marker(
      attr("id") := kind.markerId,
      attr("markerWidth") := 8,
      attr("markerHeight") := 8,
      attr("refX") := 7,
      attr("refY") := 4,
      attr("orient") := "auto"
    )(
      SVG.path(SVG.d := "M0,0 L8,4 L0,8 Z", SVG.fill := kind.color)
    )

  private def num(d: Double): String = d.toString

  private def background(layout: GraphLayout): Html[Msg] =
    SVG.rect(
      SVG.x := "-20000",
      SVG.y := "-20000",
      attr("width") := 40000 + layout.width,
      attr("height") := 40000 + layout.height,
      SVG.fill := "transparent",
      onClick(Msg.GraphFocus(None))
    )

  private def columnHeader(c: GraphColumn): Html[Msg] =
    SVG.textTag(
      SVG.x := num(c.x + GraphLayout.NodeW / 2),
      SVG.y := "30",
      attr("text-anchor") := "middle",
      attr("font-size") := "12",
      cls := "column-title"
    )(c.title)

  private def link(
      l: GraphLink,
      focus: Option[String],
      edgeFocus: Option[String],
      pathSel: Option[(String, Set[String])]
  ): Html[Msg] =
    val isFocused = edgeFocus.contains(l.id)
    val dimFocus  = focus.exists(f => l.fromId != f && l.toId != f)
    val dimPath   = pathSel.exists(p => !p._2.contains(l.fromId))
    val dim       = !isFocused && (dimFocus || dimPath)
    val label =
      if l.label.nonEmpty && l.kind == EdgeKind.Emits then
        val w = l.label.length * 6.0 + 12.0
        List(
          SVG.rect(
            SVG.x := num(l.labelX - w / 2),
            SVG.y := num(l.labelY - 11),
            attr("width") := w,
            attr("height") := 15,
            SVG.rx := "4",
            cls := "link-tag"
          ),
          SVG.textTag(
            SVG.x := num(l.labelX),
            SVG.y := num(l.labelY),
            attr("text-anchor") := "middle",
            attr("font-size") := "10",
            cls := "link-label"
          )(l.label)
        )
      else if l.label.nonEmpty then
        List(
          SVG.textTag(
            SVG.x := num(l.labelX),
            SVG.y := num(l.labelY),
            attr("text-anchor") := "middle",
            attr("font-size") := "10",
            cls := "link-label"
          )(l.label)
        )
      else Nil
    // §13 match: a branched decide shows its conditions as a chip under the edge label.
    val caseChip =
      if l.cases.isEmpty then Nil
      else
        val text  = l.cases.map(c => "\"" + c + "\"").mkString(" / ")
        val shown = if text.length > 34 then text.take(33) + "…" else text
        val w     = shown.length * 5.4 + 12.0
        val cy    = if l.label.nonEmpty then l.labelY + 17 else l.labelY
        List(
          SVG.rect(
            SVG.x := num(l.labelX - w / 2),
            SVG.y := num(cy - 11),
            attr("width") := w,
            attr("height") := 14,
            SVG.rx := "4",
            cls := "link-tag case-tag"
          ),
          SVG.textTag(
            SVG.x := num(l.labelX),
            SVG.y := num(cy),
            attr("text-anchor") := "middle",
            attr("font-size") := "9",
            cls := "link-label case-label"
          )(shown)
        )
    SVG.g(
      cls := s"link kind-${l.kind.css}${if dim then " dim" else ""}${if isFocused then " focused" else ""}",
      onClick(
        if isFocused then Msg.GraphEdgeFocus(None) else Msg.GraphEdgeFocus(Some(l.id))
      ).useStopPropagation,
      onDoubleClick(Msg.GraphPath(Some(l.fromId))).useStopPropagation
    )(
      SVG.path(
        SVG.d := l.d,
        cls := "link-path",
        attr("marker-end") := s"url(#${l.kind.markerId})"
      ) :: label ::: caseChip *
    )

  private def node(
      n: GraphNode,
      focus: Option[String],
      near: Set[String],
      pathSel: Option[(String, Set[String])]
  ): Html[Msg] =
    val pathStart = pathSel.map(_._1)
    val focused   = focus.contains(n.id) || pathStart.contains(n.id)
    val dimFocus  = focus.isDefined && !focused && !near.contains(n.id)
    val dimPath   = pathSel.exists(p => !p._2.contains(n.id))
    val dimmed    = dimFocus || dimPath
    SVG.g(
      cls := s"node kind-${n.kind.css}${if focused then " focused" else ""}${if dimmed then " dim" else ""}",
      attr("transform") := s"translate(${n.x}, ${n.y})",
      onMouseDown(_ => Msg.NoOp).useStopPropagation,
      onClick(
        if focused then Msg.GraphFocus(None) else Msg.GraphFocus(Some(n.id))
      ).useStopPropagation,
      onDoubleClick(Msg.GraphPath(Some(n.id))).useStopPropagation
    )(
      SVG.rect(
        SVG.x := "0",
        SVG.y := "0",
        attr("width") := GraphLayout.NodeW,
        attr("height") := GraphLayout.NodeH,
        SVG.rx := "8",
        cls := "node-box"
      ),
      SVG.textTag(
        SVG.x := num(GraphLayout.NodeW / 2),
        SVG.y := num(GraphLayout.NodeH / 2 + 5),
        attr("text-anchor") := "middle",
        attr("font-size") := "13",
        cls := "node-label"
      )(truncate(n.label))
    )

  private def details(m: ContextModel, links: List[GraphLink], id: String): Option[Html[Msg]] =
    m.declarations.find(_.name == id).map { d =>
      val outgoing = links.filter(_.fromId == id)
      val incoming = links.filter(_.toId == id)
      div(cls := "graph-details")(
        div(cls := "details-head")(
          span(cls := s"kind-badge ${d.kind.css}")(d.kind.label),
          code(d.name),
          button(onClick(Msg.GraphFocus(None)), cls := "btn small")("✕")
        ),
        d.underlying.map(u => div(cls := "desc")(s"underlying $u")).orEmpty,
        d.description.map(t => div(cls := "desc")(t)).orEmpty,
        Option.when(d.fields.nonEmpty)(
          div(cls := "details-fields")(
            d.fields.map(f => div(cls := "field-row")(code(f.name), span(cls := "type")(f.typeName)))*
          )
        ).orEmpty,
        Option.when(d.variants.nonEmpty)(
          div(cls := "details-fields")(d.variants.map(v => div(cls := "field-row")(code(v)))*)
        ).orEmpty,
        Option.when(incoming.nonEmpty || outgoing.nonEmpty)(
          div(cls := "details-connections")(
            incoming.map(l => connRow(l, incoming = true)) :::
              outgoing.map(l => connRow(l, incoming = false)) *
          )
        ).orEmpty
      )
    }

  private def connRow(l: GraphLink, incoming: Boolean): Html[Msg] =
    div(cls := "conn-row")(
      span(cls := s"conn-kind ${l.kind.css}")(l.kind.css + (if incoming then " in" else " out")),
      code(if incoming then l.fromId else l.toId),
      span(cls := "conn-label")(l.label)
    )

  private def truncate(s: String): String =
    if s.length > 24 then s.take(23) + "…" else s
