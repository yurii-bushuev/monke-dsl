package kek.dsl

enum Severity:
  case Error, Warning

  def label: String = this match
    case Error   => "error"
    case Warning => "warning"

final case class Diagnostic(severity: Severity, message: String, span: Span):
  def render: String = s"Ln ${span.line}, Col ${span.col}: ${severity.label}: $message"

object Diagnostic:
  def error(message: String, span: Span): Diagnostic = Diagnostic(Severity.Error, message, span)
