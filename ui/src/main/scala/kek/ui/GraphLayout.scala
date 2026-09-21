package kek.ui

import kek.dsl.{ContextModel, DeclKind, Edge}

enum GraphKind:
  case Actor, Command, Aggregate, DomainEvent, IntegrationEvent, Error

  def css: String = this match
    case GraphKind.Actor            => "actor"
    case GraphKind.Command          => "cmd"
    case GraphKind.Aggregate        => "agg"
    case GraphKind.DomainEvent      => "evt"
    case GraphKind.IntegrationEvent => "ie"
    case GraphKind.Error            => "err"

enum EdgeKind:
  case Sends, Handles, Emits, TriggeredBy, Fails

  def css: String = this match
    case EdgeKind.Sends       => "sends"
    case EdgeKind.Handles     => "handles"
    case EdgeKind.Emits       => "emits"
    case EdgeKind.TriggeredBy => "triggered"
    case EdgeKind.Fails       => "fails"

  def markerId: String = s"arrow-$css"

  def color: String = this match
    case EdgeKind.Sends       => "#9ece6a"
    case EdgeKind.Handles     => "#7aa2f7"
    case EdgeKind.Emits       => "#e0af68"
    case EdgeKind.TriggeredBy => "#bb9af7"
    case EdgeKind.Fails       => "#f7768e"

  def legend: String = this match
    case EdgeKind.Sends       => "The actor initiates this command."
    case EdgeKind.Handles     => "The behavior decides the command and produces the domain event; branched decides list their cases."
    case EdgeKind.Emits       => "The transition applies the event to the aggregate state and emits the integration event."
    case EdgeKind.TriggeredBy => "When this integration event arrives (e.g. via outbox), it issues the command."
    case EdgeKind.Fails       => "The decision can fail with this domain error."

final case class GraphNode(id: String, kind: GraphKind, label: String, x: Double, y: Double)

/** `aggs` — the aggregate roots this link belongs to; drives the per-aggregate quick filter.
  * `name` — the method name this edge translates to in code, taken from Edge.methodName — the one
  * home of the decide<Command>/on<Event> convention, shared with the future code generator.
  * `cases` — the §13 `match` conditions that produce this link's outcome; empty unless the decide
  * is branched (arms sharing an outcome share one curve and accumulate their conditions).
  */
final case class GraphLink(
    id: String,
    d: String,
    fromId: String,
    toId: String,
    kind: EdgeKind,
    label: String,
    labelX: Double,
    labelY: Double,
    name: String = "",
    aggs: Set[String] = Set.empty,
    cases: List[String] = Nil
)
final case class GraphColumn(title: String, x: Double)

/** Layered left-to-right layout: Actors → Commands → Events & errors → Integration Events.
  * Domain events and errors share one outcomes column (success vs failure edges differ by color);
  * aggregate roots are not a column — the state transition `S → S'` is shown as a tag on the
  * transition edge.
  */
final case class GraphLayout(nodes: List[GraphNode], links: List[GraphLink], columns: List[GraphColumn],
    width: Double, height: Double)

object GraphLayout:

  val NodeW = 200.0
  val NodeH = 36.0

  private val GapX      = 150.0
  private val GapY      = 40.0
  private val Pad       = 48.0
  private val HeaderH   = 30.0

  /** A prospective link straight from a model edge — before geometry and before §13 arms sharing
    * an outcome are merged onto one curve. `name` is the edge's codegen method name
    * (Edge.methodName), never derived here.
    */
  private final case class RawLink(
      fromId: String,
      toId: String,
      kind: EdgeKind,
      label: String,
      name: String,
      cases: List[String],
      aggs: Set[String]
  )

  def apply(m: ContextModel): GraphLayout =
    def entries(kind: DeclKind, graphKind: GraphKind): List[(String, GraphKind)] =
      m.byKind(kind).map(_.name).sorted.map(n => (n, graphKind))

    val columns: List[(String, List[(String, GraphKind)])] = List(
      ("Actors", entries(DeclKind.ActorKind, GraphKind.Actor)),
      ("Commands", entries(DeclKind.CommandKind, GraphKind.Command)),
      ("Events & errors",
        entries(DeclKind.DomainEventKind, GraphKind.DomainEvent) ++
          entries(DeclKind.ErrorKind, GraphKind.Error)),
      ("Integration events", entries(DeclKind.IntegrationEventKind, GraphKind.IntegrationEvent))
    )

    val colHeights = columns.map(c => math.max(0.0, c._2.size * NodeH + (c._2.size - 1) * GapY))
    val contentH   = colHeights.max
    val height     = contentH + Pad * 2 + HeaderH
    val width      = Pad * 2 + columns.size * NodeW + (columns.size - 1) * GapX

    val nodeXs = columns.indices.map(k => Pad + k * (NodeW + GapX)).toList

    val nodes =
      columns.zip(nodeXs).zip(colHeights).flatMap { case ((col, x), colH) =>
        val (_, entries) = col
        val top = Pad + HeaderH + (contentH - colH) / 2
        entries.zipWithIndex.map { case ((name, kind), i) =>
          GraphNode(name, kind, name, x, top + i * (NodeH + GapY))
        }
      }.toList

    val pos: Map[String, (Double, Double)] = nodes.map(n => n.id -> ((n.x, n.y))).toMap

    // One edge per handles pairing: Command → Domain Event, labeled with the method name the edge
    // translates to in code (decide<Command>). Branched decides (§13 match) add one edge per event
    // arm, plus one fails edge per fail-arm error, each carrying its condition. Aggregate membership
    // per command / integration event, for the quick filter: a link belongs to the aggregate(s)
    // whose behavior handles or emits it — including trigger links, which belong to both the
    // emitting aggregate (its event flows out) and the handling one (its command is invoked).
    val handlerAggs: Map[String, Set[String]] =
      m.edges
        .collect { case Edge.Handles(_, agg, cmd, _, _, _) => (cmd, agg) }
        .groupMap(_._1)(_._2)
        .view.mapValues(_.toSet).toMap

    val emitterAggs: Map[String, Set[String]] =
      m.edges.flatMap {
        case Edge.Transition(_, agg, _, _, _, emits) => emits.map(ie => (ie, agg))
        case _                                       => Nil
      }.groupMap(_._1)(_._2)
        .view.mapValues(_.toSet).toMap

    val rawLinks: List[RawLink] = m.edges.flatMap {
      case Edge.Sends(actor, cmd) =>
        List(RawLink(actor, cmd, EdgeKind.Sends, "", "", Nil, handlerAggs.getOrElse(cmd, Set.empty)))
      case h: Edge.Handles =>
        List(RawLink(h.command, h.event, EdgeKind.Handles, h.methodName, h.methodName,
          h.when.toList, Set(h.aggregate)))
      case f: Edge.Fails =>
        List(RawLink(f.command, f.error, EdgeKind.Fails, "", "", f.when.toList, Set(f.aggregate)))
      case Edge.Triggers(ie, cmd) =>
        List(RawLink(ie, cmd, EdgeKind.TriggeredBy, "triggered", "", Nil,
          emitterAggs.getOrElse(ie, Set.empty) ++ handlerAggs.getOrElse(cmd, Set.empty)))
      case t: Edge.Transition =>
        t.emits.map(ie => RawLink(t.event, ie, EdgeKind.Emits, s"${t.from} → ${t.to}",
          t.methodName, Nil, Set(t.aggregate)))
      case _: Edge.OperatesOn | _: Edge.Composes | _: Edge.FieldType => Nil
    }

    // Branched decides (§13 match): arms sharing an outcome share one curve — their conditions
    // accumulate on the link so every case stays visible without duplicating the edge.
    val links = rawLinks
      .groupMap(l => (l.fromId, l.toId, l.kind))(identity)
      .values
      .map { rs =>
        val head = rs.head
        head.copy(cases = rs.flatMap(_.cases).distinct, aggs = rs.flatMap(_.aggs).toSet)
      }
      .toList
      .flatMap { r =>
        for
          (fx, fy) <- pos.get(r.fromId)
          (tx, ty) <- pos.get(r.toId)
        yield
          val sx      = fx + NodeW
          val sy      = fy + NodeH / 2
          val txStart = tx
          val tyStart = ty + NodeH / 2
          // Backward edges (integration event → the command it triggers) loop under the diagram as a
          // return bus so they cross no nodes.
          val (d, lx, ly) =
            if fx > tx then
              val sxb = fx + NodeW / 2
              val syb = fy + NodeH
              val txb = tx + NodeW / 2
              val tyb = ty + NodeH
              val by  = math.max(fy, ty) + NodeH + 56
              (s"M $sxb $syb C $sxb $by, $txb $by, $txb $tyb", (sxb + txb) / 2, by + 16)
            else
              val mx = (sx + txStart) / 2
              (s"M $sx $sy C $mx $sy, $mx $tyStart, $txStart $tyStart", mx, (sy + tyStart) / 2 - 6)
          GraphLink(
            id = s"${r.fromId}>${r.toId}:${r.kind.css}",
            d = d,
            fromId = r.fromId,
            toId = r.toId,
            kind = r.kind,
            label = r.label,
            labelX = lx,
            labelY = ly,
            name = r.name,
            aggs = r.aggs,
            cases = r.cases
          )
      }

    val columnTitles = columns.zip(nodeXs).map { (col, x) => GraphColumn(col._1, x) }

    GraphLayout(nodes, links, columnTitles, width, height)
