package kek.ui

/** Tests the graph pane's view-state module through its interface — selection exclusivity, zoom
  * clamping and cursor anchoring, drag panning, path reachability — with no Tyrian, DOM, or
  * rendering involved.
  */
class GraphStateTests extends munit.FunSuite:

  private val links = List(
    GraphLink("A>B:handles", "d", "A", "B", EdgeKind.Handles, "", 0, 0),
    GraphLink("B>C:emits", "d", "B", "C", EdgeKind.Emits, "", 0, 0),
    GraphLink("C>D:emits", "d", "C", "D", EdgeKind.Emits, "", 0, 0)
  )

  test("selecting a node clears edge and path selections"):
    val s = GraphState().selectEdge(Some("A>B:handles")).followPath(Some("B")).selectNode(Some("A"))
    assertEquals(s.focus, Some("A"))
    assertEquals(s.edgeFocus, None)
    assertEquals(s.path, None)

  test("selecting an edge clears node and path selections"):
    val s = GraphState().selectNode(Some("A")).followPath(Some("B")).selectEdge(Some("A>B:handles"))
    assertEquals(s.edgeFocus, Some("A>B:handles"))
    assertEquals(s.focus, None)
    assertEquals(s.path, None)

  test("following a path clears node and edge selections"):
    val s = GraphState().selectNode(Some("A")).selectEdge(Some("A>B:handles")).followPath(Some("B"))
    assertEquals(s.path, Some("B"))
    assertEquals(s.focus, None)
    assertEquals(s.edgeFocus, None)

  test("clearing any selection clears all of them"):
    assertEquals(GraphState(focus = Some("A")).selectNode(None), GraphState())
    assertEquals(GraphState(edgeFocus = Some("A>B:handles")).selectEdge(None), GraphState())
    assertEquals(GraphState(path = Some("B")).followPath(None), GraphState())

  test("zoom steps are clamped to the toolbar range"):
    assertEquals(GraphState().zoomBy(-5).zoom, GraphState.ZoomMin)
    assertEquals(GraphState().zoomBy(5).zoom, GraphState.ZoomMax)

  test("zoom-at keeps the point under the cursor fixed"):
    val start    = GraphState(panX = 40, panY = -20, zoom = 1.25)
    val (x, y)   = (300.0, 200.0)
    val s        = start.zoomAt(0.3, x, y)
    val contentX = (x - start.panX) / start.zoom
    val contentY = (y - start.panY) / start.zoom
    assertEqualsDouble((x - s.panX) / s.zoom, contentX, 1e-9)
    assertEqualsDouble((y - s.panY) / s.zoom, contentY, 1e-9)

  test("zoom-at at the clamp limit changes nothing"):
    val s = GraphState(zoom = GraphState.ZoomMax)
    assertEquals(s.zoomAt(0.3, 10, 10), s)

  test("drag start-move-end pans by the pointer delta"):
    val s = GraphState().dragStart(100, 50).dragMove(140, 55)
    assertEqualsDouble(s.panX, 40.0, 1e-9)
    assertEqualsDouble(s.panY, 5.0, 1e-9)
    assertEquals(s.dragEnd.drag, None)

  test("drag moves without a start are ignored"):
    assertEquals(GraphState().dragMove(10, 10), GraphState())

  test("reset view restores the default state"):
    val s = GraphState(zoom = 2, panX = 30, panY = 40, drag = Some(Drag(1, 2, 3, 4))).resetView
    assertEquals(s, GraphState())

  test("filter selects one aggregate or all"):
    val s = GraphState().filterBy(Some("Payment"))
    assertEquals(s.filter, Some("Payment"))
    assertEquals(s.filterBy(None), GraphState())

  test("reachableFrom follows the directed flow transitively"):
    assertEquals(GraphState.reachableFrom(links, "A"), Set("A", "B", "C", "D"))
    assertEquals(GraphState.reachableFrom(links, "C"), Set("C", "D"))
    assertEquals(GraphState.reachableFrom(links, "D"), Set("D"))

  test("adjacency is the node plus its immediate neighbours"):
    assertEquals(GraphState.adjacency(links, "B"), Set("A", "B", "C"))
    assertEquals(GraphState.adjacency(links, "D"), Set("C", "D"))
