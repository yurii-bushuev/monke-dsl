package kek.dsl

class LexerTests extends munit.FunSuite:

  test("keywords are plain identifiers — the language has no reserved words") {
    val (tokens, diags) = Lexer.tokenize("value PaymentId;")
    assertEquals(diags, Nil)
    assertEquals(
      tokens.map(t => (t.kind, t.text)),
      List(
        (TokenKind.Identifier, "value"),
        (TokenKind.Identifier, "PaymentId"),
        (TokenKind.Punct, ";"),
        (TokenKind.Eof, "")
      )
    )
  }

  test("annotation lexes as a single token") {
    val (tokens, _) = Lexer.tokenize("@aggregate-root")
    assertEquals(tokens.head.kind, TokenKind.Annotation)
    assertEquals(tokens.head.text, "@aggregate-root")
  }

  test("equals lexes as a single token") {
    val (tokens, _) = Lexer.tokenize("value X = UUID;")
    assertEquals(
      tokens.map(_.text),
      List("value", "X", "=", "UUID", ";", "")
    )
  }

  test("arrow is a single token") {
    val (tokens, _) = Lexer.tokenize("A -> B")
    assertEquals(tokens.map(_.text), List("A", "->", "B", ""))
  }

  test("string literal with escaped quotes") {
    val (tokens, diags) = Lexer.tokenize("\"say \\\"hi\\\"\"")
    assertEquals(diags, Nil)
    assertEquals(tokens.head.kind, TokenKind.Str)
    assertEquals(tokens.head.text, "\"say \\\"hi\\\"\"")
  }

  test("comments are skipped") {
    val (tokens, diags) = Lexer.tokenize("// line\n/* block */ value")
    assertEquals(diags, Nil)
    assertEquals(tokens.filterNot(_.kind == TokenKind.Eof).map(_.text), List("value"))
  }

  test("unterminated string is a diagnostic") {
    val (_, diags) = Lexer.tokenize("\"open")
    assertEquals(diags.length, 1)
    assert(diags.head.message.startsWith("Unterminated string"))
  }

  test("unterminated block comment is a diagnostic") {
    val (_, diags) = Lexer.tokenize("/* never closed")
    assertEquals(diags.length, 1)
  }

  test("line and column are tracked") {
    val (tokens, _) = Lexer.tokenize("a\n  bb")
    val bb = tokens(1)
    assertEquals(bb.span.line, 2)
    assertEquals(bb.span.col, 3)
  }

  test("illegal character is a diagnostic") {
    val (_, diags) = Lexer.tokenize("a # b")
    assert(diags.exists(_.message.contains("#")))
  }
