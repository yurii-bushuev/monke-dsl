package kek.dsl

/** End-to-end compiler entry point: lex → parse → check → model. */
object Compiler:

  final case class Result(diagnostics: List[Diagnostic], model: Option[ContextModel]):
    lazy val errors: List[Diagnostic] = diagnostics.filter(_.severity == Severity.Error)
    lazy val hasErrors: Boolean      = errors.nonEmpty

  def compile(source: String): Result =
    val (tokens, lexDiags) = Lexer.tokenize(source)
    val parsed             = Parser.parse(tokens, lexDiags)
    val parseFailed        = parsed.context.isEmpty ||
      parsed.diagnostics.exists(_.severity == Severity.Error)
    if parseFailed then Result(parsed.diagnostics, None)
    else
      val context = parsed.context.get
      val diags   = Checks.check(context)
      if diags.exists(_.severity == Severity.Error) then Result(parsed.diagnostics ++ diags, None)
      else Result(parsed.diagnostics ++ diags, Some(ContextModel.build(context)))
