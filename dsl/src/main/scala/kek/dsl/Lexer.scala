package kek.dsl

import scala.collection.mutable.ListBuffer

enum TokenKind:
  case Identifier, Str, Annotation, Punct, Eof

final case class Token(kind: TokenKind, text: String, span: Span):
  def isPunct(p: String): Boolean   = kind == TokenKind.Punct && text == p
  def isIdentifier: Boolean         = kind == TokenKind.Identifier
  def isIdentifier(s: String): Boolean = isIdentifier && text == s

  def render: String = if text.isEmpty then "end of input" else s"'$text'"

/** Tokenizer for the lexical grammar in PROPOSAL.md §20–§21.
  *
  * Keywords are not reserved: the language has no reserved words, so words like `value` or `on` lex as plain
  * identifiers and the parser matches them contextually. Annotations (`@name`) lex as single tokens. Lexical
  * errors (unterminated strings/comments, illegal characters) are reported as diagnostics without stopping.
  */
object Lexer:

  def tokenize(source: String): (List[Token], List[Diagnostic]) =
    val tokens    = ListBuffer.empty[Token]
    val diags     = ListBuffer.empty[Diagnostic]
    val src       = source
    val len       = src.length
    var i         = 0
    var line      = 1
    var col       = 1

    def advance(): Char =
      val c = src.charAt(i)
      i += 1
      if c == '\n' then { line += 1; col = 1 } else col += 1
      c

    /** The character `n` positions after the current one (1 = immediately after). */
    def lookahead(n: Int): Option[Char] = if i + n < len then Some(src.charAt(i + n)) else None

    def error(msg: String, atLine: Int, atCol: Int): Unit =
      diags += Diagnostic.error(msg, Span(i, i + 1, atLine, atCol))

    while i < len do
      val startLine = line
      val startCol  = col
      val c         = src.charAt(i)

      if c.isWhitespace then advance()
      else if c == '/' && lookahead(1).contains('/') then
        while i < len && src.charAt(i) != '\n' do advance()
      else if c == '/' && lookahead(1).contains('*') then
        advance(); advance()
        var closed = false
        while i < len && !closed do
          if src.charAt(i) == '*' && lookahead(1).contains('/') then { advance(); advance(); closed = true }
          else advance()
        if !closed then error("Unterminated block comment", startLine, startCol)
      else if c == '"' then
        val start = i
        advance()
        var terminated = false
        while i < len && !terminated do
          val sc = advance()
          if sc == '\\' && i < len then advance()
          else if sc == '"' then terminated = true
        if !terminated then error("Unterminated string literal", startLine, startCol)
        tokens += Token(TokenKind.Str, src.substring(start, i), Span(start, i, startLine, startCol))
      else if c == '@' then
        val start = i
        advance()
        while i < len && (src.charAt(i).isLetterOrDigit || src.charAt(i) == '_' || src.charAt(i) == '-') do
          advance()
        val name = src.substring(start, i)
        if name == "@" then error("Expected an annotation name after '@'", startLine, startCol)
        tokens += Token(TokenKind.Annotation, name, Span(start, i, startLine, startCol))
      else if c.isLetter || c == '_' then
        val start = i
        advance()
        var scanning = true
        while i < len && scanning do
          val ch = src.charAt(i)
          // Hyphens are part of identifiers (keywords like `bounded-context`) unless they begin an arrow.
          if ch.isLetterOrDigit || ch == '_' then advance()
          else if ch == '-' && lookahead(1).exists(_ != '>') then advance()
          else scanning = false
        tokens += Token(TokenKind.Identifier, src.substring(start, i), Span(start, i, startLine, startCol))
      else if c == '-' && lookahead(1).contains('>') then
        val start = i
        advance(); advance()
        tokens += Token(TokenKind.Punct, "->", Span(start, i, startLine, startCol))
      else if "{}();:,=".indexOf(c) >= 0 then
        val start = i
        advance()
        tokens += Token(TokenKind.Punct, c.toString, Span(start, i, startLine, startCol))
      else
        error(s"Illegal character '$c'", startLine, startCol)
        advance()

    tokens += Token(TokenKind.Eof, "", Span(len, len, line, col))
    (tokens.toList, diags.toList)
