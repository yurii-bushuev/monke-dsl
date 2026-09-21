package kek.dsl

/** Source range of a token or AST node.
  *
  * Offsets are 0-based character positions into the source, `end` is exclusive. Line and column are 1-based.
  */
final case class Span(start: Int, end: Int, line: Int, col: Int)
