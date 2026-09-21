package kek.ui

/** The graph pane's view state and every transition on it. All pane invariants live here so they
  * hold everywhere and are testable without Tyrian or a DOM:
  *   - at most one selection is active — node focus, edge focus, or double-click path — and
  *     clearing any of them clears all (they are mutually exclusive by construction);
  *   - zoom stays within [ZoomMin, ZoomMax], and zoom-at-pointer keeps the point under the cursor
  *     fixed (pan' = p − (p − pan)·z'/z);
  *   - drag state exists only between dragStart and dragEnd.
  *
  * The Elm update in TyrianApp is a thin adapter at this module's interface.
  */
final case class GraphState(
    focus: Option[String] = None,
    edgeFocus: Option[String] = None,
    path: Option[String] = None,
    zoom: Double = 1.0,
    panX: Double = 0.0,
    panY: Double = 0.0,
    drag: Option[Drag] = None,
    filter: Option[String] = None
):

  def selectNode(id: Option[String]): GraphState    = copy(focus = id, edgeFocus = None, path = None)
  def selectEdge(id: Option[String]): GraphState    = copy(focus = None, edgeFocus = id, path = None)
  def followPath(start: Option[String]): GraphState = copy(focus = None, edgeFocus = None, path = start)

  def zoomBy(delta: Double): GraphState = copy(zoom = clamped(zoom + delta))

  def zoomAt(delta: Double, x: Double, y: Double): GraphState =
    val z1 = clamped(zoom + delta)
    if z1 == zoom then this
    else
      val cx = (x - panX) / zoom
      val cy = (y - panY) / zoom
      copy(zoom = z1, panX = x - cx * z1, panY = y - cy * z1)

  def resetView: GraphState = copy(zoom = 1.0, panX = 0.0, panY = 0.0, drag = None)

  def dragStart(x: Double, y: Double): GraphState = copy(drag = Some(Drag(x, y, panX, panY)))

  def dragMove(x: Double, y: Double): GraphState = drag match
    case Some(d) => copy(panX = d.panX0 + (x - d.startX), panY = d.panY0 + (y - d.startY))
    case None    => this

  def dragEnd: GraphState = copy(drag = None)

  def filterBy(aggregate: Option[String]): GraphState = copy(filter = aggregate)

  private def clamped(z: Double): Double = math.min(GraphState.ZoomMax, math.max(GraphState.ZoomMin, z))

final case class Drag(startX: Double, startY: Double, panX0: Double, panY0: Double)

object GraphState:

  val ZoomMin = 0.3
  val ZoomMax = 2.5

  /** Double-click follows the directed flow: every node reachable from `start` stays lit. */
  def reachableFrom(links: List[GraphLink], start: String): Set[String] =
    val out: Map[String, List[GraphLink]] =
      links.groupBy(_.fromId).view.mapValues(_.toList).toMap
    var seen     = Set(start)
    var frontier = List(start)
    while frontier.nonEmpty do
      val next = frontier.flatMap(id => out.getOrElse(id, Nil).map(_.toId)).distinct
      frontier = next.filterNot(seen.contains)
      seen = seen ++ frontier
    seen

  /** The node itself plus everything directly connected to it. */
  def adjacency(links: List[GraphLink], id: String): Set[String] =
    links.flatMap(l => if l.fromId == id || l.toId == id then List(l.fromId, l.toId) else Nil).toSet
