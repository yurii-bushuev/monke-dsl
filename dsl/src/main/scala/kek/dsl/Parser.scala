package kek.dsl

import Ast.*
import scala.collection.mutable.ListBuffer

final case class ParseResult(context: Option[BoundedContext], diagnostics: List[Diagnostic])

/** Hand-written recursive-descent parser for the EBNF in PROPOSAL.md §2–§19.
  *
  * The grammar has no reserved words: keywords are identifiers matched contextually. On a syntax error the
  * parser records a diagnostic and resynchronizes to the next `;` or `}` so a single run reports as many
  * errors as possible.
  */
object Parser:

  def parse(tokens: List[Token], diagnostics: List[Diagnostic]): ParseResult =
    val p = new Impl(tokens.toArray)
    try
      val ctx = p.document()
      ParseResult(Some(ctx), diagnostics ++ p.diags.toList)
    catch
      case Fail(d) => ParseResult(None, diagnostics ++ (p.diags += d).toList)

  private final case class Fail(diagnostic: Diagnostic)
      extends RuntimeException(diagnostic.message, null, false, false)

  private def unquote(raw: String): String =
    val body     = raw.drop(1)
    val stripped = if body.nonEmpty && body.endsWith("\"") then body.dropRight(1) else body
    val sb       = new StringBuilder
    var i        = 0
    while i < stripped.length do
      val c = stripped.charAt(i)
      if c == '\\' && i + 1 < stripped.length then
        stripped.charAt(i + 1) match
          case 'n'  => sb += '\n'
          case 't'  => sb += '\t'
          case 'r'  => sb += '\r'
          case '"'  => sb += '"'
          case '\\' => sb += '\\'
          case o    => sb += '\\'; sb += o
        i += 2
      else
        sb += c
        i += 1
    sb.result()

  private final class Impl(tokens: Array[Token]):
    val diags = ListBuffer.empty[Diagnostic]
    private var pos = 0

    private def peek: Token  = tokens(pos)
    private def peek2: Token = tokens(math.min(pos + 1, tokens.length - 1))
    private def last: Token  = tokens(math.max(pos - 1, 0))

    private def advance(): Token =
      val t = tokens(pos)
      if pos < tokens.length - 1 then pos += 1
      t

    private def fail(msg: String, at: Token = peek): Nothing =
      throw Fail(Diagnostic.error(msg, at.span))

    private def spanFrom(start: Token): Span =
      Span(start.span.start, last.span.end, start.span.line, start.span.col)

    private def expectIdentifier(what: String): Token =
      if peek.isIdentifier then advance()
      else fail(s"Expected $what but found ${peek.render}")

    private def expectPunct(p: String): Unit =
      if peek.isPunct(p) then advance()
      else fail(s"Expected '$p' but found ${peek.render}")

    private def expectKeyword(kw: String): Token =
      if peek.isIdentifier(kw) then advance()
      else fail(s"Expected '$kw' but found ${peek.render}")

    private def stringLit(): String =
      if peek.kind == TokenKind.Str then unquote(advance().text)
      else fail(s"Expected a string literal but found ${peek.render}")

    private def typeRef(): TypeRef =
      val t = expectIdentifier("a type reference")
      TypeRef(t.text, t.span)

    private def fields(what: String): List[Field] =
      val buf = ListBuffer.empty[Field]
      while peek.isIdentifier && peek2.isPunct(":") do
        val name = advance()
        expectPunct(":")
        val tpe  = typeRef()
        expectPunct(";")
        buf += Field(name.text, name.span, tpe)
      if peek.isIdentifier then
        fail(s"Expected '$what' field syntax 'name: Type;' for '${peek.text}'", peek)
      buf.toList

    private def annotations(): List[Annotation] =
      val buf = ListBuffer.empty[Annotation]
      while peek.kind == TokenKind.Annotation do
        val t = advance()
        t.text match
          case "@description" =>
            expectPunct("(")
            val s = stringLit()
            expectPunct(")")
            buf += Annotation.Description(s, t.span)

          case "@deprecated" =>
            val reason =
              if peek.isPunct("(") then
                advance()
                val s = stringLit()
                expectPunct(")")
                Some(s)
              else None
            buf += Annotation.Deprecated(reason, t.span)

          case "@external-link" =>
            expectPunct("(")
            val first = stringLit()
            val ann =
              if peek.isPunct(",") then
                advance()
                Annotation.ExternalLink(Some(first), stringLit(), t.span)
              else Annotation.ExternalLink(None, first, t.span)
            expectPunct(")")
            buf += ann

          case "@tags" =>
            expectPunct("(")
            val tags = ListBuffer(stringLit())
            while peek.isPunct(",") do
              advance()
              tags += stringLit()
            expectPunct(")")
            buf += Annotation.Tags(tags.toList, t.span)

          case "@aggregate-root" =>
            if peek.isPunct("(") then fail("@aggregate-root takes no arguments", peek)
            buf += Annotation.AggregateRoot(t.span)

          case other =>
            fail(s"Unknown annotation '$other' (§19 lists the supported annotations)", t)
      buf.toList

    def document(): BoundedContext =
      val contextAnnotations =
        try annotations()
        catch
          case Fail(d) =>
            diags += d
            Nil
      val kw   = expectKeyword("bounded-context")
      val name = expectIdentifier("a bounded-context name")
      expectPunct("{")
      val decls = ListBuffer.empty[Declaration]
      while !peek.isPunct("}") && peek.kind != TokenKind.Eof do
        try decls += declaration()
        catch
          case Fail(d) =>
            diags += d
            synchronizeDeclaration()
      expectPunct("}")
      if peek.kind != TokenKind.Eof then
        fail(
          "Unexpected " + peek.render + " after the bounded-context; " +
            "a file contains exactly one bounded-context (§23.1)"
        )
      BoundedContext(name.text, contextAnnotations, decls.toList, spanFrom(kw))

    private def synchronizeDeclaration(): Unit =
      while !peek.isPunct(";") && !peek.isPunct("}") && peek.kind != TokenKind.Eof do advance()
      if peek.isPunct(";") then advance()

    private def declaration(): Declaration =
      val start = peek
      val anns  = annotations()
      val kind  = expectIdentifier(
        "a declaration keyword (value, entity, command, event, integration-event, error, domain-entity)"
      )
      kind.text match
        case "value"             => valueDeclaration(start, anns)
        case "entity"            => entityDeclaration(start, anns)
        case "domain-entity"     => domainEntityDeclaration(start, anns)
        case "actor"             => actorDeclaration(start, anns)
        case "command" | "event" | "integration-event" | "error" =>
          fieldedDeclaration(start, anns, kind)
        case other =>
          fail(
            s"Unknown declaration '$other'; expected value, entity, command, event, " +
              "integration-event, error, domain-entity or actor",
            kind
          )

    private def valueDeclaration(start: Token, anns: List[Annotation]): Declaration.Value =
      val name = expectIdentifier("a value name")
      peek match
        case t if t.isPunct("=") =>
          advance()
          val underlying = typeRef()
          expectPunct(";")
          Declaration.Value(name.text, ValueBody.Atomic(Some(underlying)), anns, spanFrom(start))
        case t if t.isPunct(";") =>
          advance()
          Declaration.Value(name.text, ValueBody.Atomic(None), anns, spanFrom(start))
        case t if t.isPunct("{") =>
          advance()
          val body =
            if peek.isPunct("}") then ValueBody.Product(Nil)
            else if peek2.isPunct(":") then ValueBody.Product(fields("value"))
            else ValueBody.Sum(variants())
          expectPunct("}")
          Declaration.Value(name.text, body, anns, spanFrom(start))
        case _ =>
          fail(s"Expected ';' or '{' after value name '${name.text}'")

    private def variants(): List[Variant] =
      val buf = ListBuffer.empty[Variant]
      while peek.isIdentifier do
        val name = advance()
        val fs =
          if peek.isPunct("{") then
            advance()
            val fs = fields("variant")
            expectPunct("}")
            fs
          else Nil
        buf += Variant(name.text, name.span, fs)
      buf.toList

    private def entityDeclaration(start: Token, anns: List[Annotation]): Declaration.Entity =
      val name   = expectIdentifier("an entity name")
      var isRoot = anns.exists(_.isInstanceOf[Annotation.AggregateRoot])
      if peek.kind == TokenKind.Annotation then
        if peek.text == "@aggregate-root" then
          advance()
          isRoot = true
        else fail(s"Only @aggregate-root may appear after the entity name (found '${peek.text}')", peek)
      expectPunct("{")
      val fs = fields("entity")
      expectPunct("}")
      Declaration.Entity(name.text, isRoot, fs, anns, spanFrom(start))

    private def actorDeclaration(start: Token, anns: List[Annotation]): Declaration.Actor =
      val name = expectIdentifier("an actor name")
      val triggers =
        peek match
          case t if t.isPunct(";") =>
            advance(); Nil
          case t if t.isPunct("{") =>
            advance()
            val buf = ListBuffer.empty[TypeRef]
            while peek.isIdentifier("triggers") do
              advance()
              buf += typeRef()
            expectPunct("}")
            buf.toList
          case _ =>
            fail(s"Expected ';' or '{' after actor name '${name.text}'")
      Declaration.Actor(name.text, triggers, anns, spanFrom(start))

    private def fieldedDeclaration(start: Token, anns: List[Annotation], kw: Token): Declaration =
      val name = expectIdentifier(s"a ${kw.text} name")
      val fs =
        peek match
          case t if t.isPunct(";") =>
            advance(); Nil
          case t if t.isPunct("{") =>
            advance()
            val fs = fields(kw.text)
            expectPunct("}")
            fs
          case _ =>
            fail(s"Expected ';' or '{' after ${kw.text} name '${name.text}'")
      kw.text match
        case "command"           => Declaration.Command(name.text, fs, anns, spanFrom(start))
        case "event"             => Declaration.DomainEvent(name.text, fs, anns, spanFrom(start))
        case "integration-event" => Declaration.IntegrationEvent(name.text, fs, anns, spanFrom(start))
        case _                   => Declaration.Error(name.text, fs, anns, spanFrom(start))

    private def domainEntityDeclaration(start: Token, anns: List[Annotation]): Declaration.DomainEntity =
      val name = expectIdentifier("a domain-entity name")
      expectKeyword("on")
      val on = typeRef()
      expectPunct("{")
      val members = ListBuffer.empty[Member]
      while !peek.isPunct("}") && peek.kind != TokenKind.Eof do
        try members += member()
        catch
          case Fail(d) =>
            diags += d
            synchronizeDeclaration()
      expectPunct("}")
      Declaration.DomainEntity(name.text, on, members.toList, anns, spanFrom(start))

    private def member(): Member =
      val start = peek
      peek match
        case t if t.isIdentifier("handles") =>
          advance()
          val command = typeRef()
          val trigger =
            if peek.isIdentifier("from") then { advance(); Some(typeRef()) }
            else None
          expectPunct("{")
          val (state, arms) =
            if peek.isIdentifier("on") then
              advance()
              val s = typeRef()
              expectPunct("->")
              val event = typeRef()
              val fails =
                if peek.isIdentifier("fails") then { advance(); typeRefList() }
                else Nil
              (s, List(Member.Arm(None, Some(event), fails, spanFrom(start))))
            else if peek.isIdentifier("match") then
              advance()
              val s = typeRef()
              expectPunct("{")
              val buf = ListBuffer.empty[Member.Arm]
              while peek.isIdentifier("case") do buf += arm()
              if buf.isEmpty then fail("A 'match' clause requires at least one 'case' arm (§13)")
              expectPunct("}")
              (s, buf.toList)
            else fail("Expected 'on' or 'match' in a command handler (§13)")
          expectPunct("}")
          Member.Handler(command, trigger, state, arms, spanFrom(start))

        case t if t.isIdentifier("transition") =>
          advance()
          val event = typeRef()
          expectPunct("{")
          expectKeyword("from")
          val from = typeRef()
          expectPunct("->")
          val to = typeRef()
          val emits =
            if peek.isIdentifier("emits") then { advance(); typeRefList() }
            else Nil
          expectPunct("}")
          Member.Transition(event, from, to, emits, spanFrom(start))

        case _ =>
          fail("Expected 'handles' or 'transition' (domain-entity members, §12–§14)")

    /** One `match` arm (§13): `case "<condition>" -> Event` or `case "<condition>" -> fails Err, ...`.
      * The condition string is documentary — the DSL defines no predicate expressions.
      */
    private def arm(): Member.Arm =
      val start = peek
      advance() // 'case'
      val when = stringLit()
      expectPunct("->")
      if peek.isIdentifier("fails") then
        advance()
        Member.Arm(Some(when), None, typeRefList(), spanFrom(start))
      else
        val event = typeRef()
        Member.Arm(Some(when), Some(event), Nil, spanFrom(start))

    /** `type-reference { "," type-reference }` — shared by `fails` and `emits` lists. */
    private def typeRefList(): List[TypeRef] =
      val buf = ListBuffer(typeRef())
      while peek.isPunct(",") do
        advance()
        buf += typeRef()
      buf.toList
