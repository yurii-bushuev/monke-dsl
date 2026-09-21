package kek.dsl

import Ast.*

class ParserTests extends munit.FunSuite:

  def parseOk(src: String): BoundedContext =
    val (tokens, lexDiags) = Lexer.tokenize(src)
    assertEquals(lexDiags, Nil)
    val res = Parser.parse(tokens, Nil)
    assertEquals(res.diagnostics, Nil, res.diagnostics.map(_.render).mkString("\n"))
    res.context.get

  test("parses the §22 example") {
    val ctx = parseOk(Sample.source)
    assertEquals(ctx.name, "Payments")
    assertEquals(ctx.declarations.length, 29)
  }

  test("actors parse with and without trigger clauses (§24)") {
    val ctx = parseOk(
      """bounded-context C {
        @description("web")
        actor WebClient {
          triggers A
          triggers B
        }
        actor Daemon;
      }"""
    )
    val actors = ctx.declarations.collect { case a: Declaration.Actor => a }
    assertEquals(actors.map(_.name), List("WebClient", "Daemon"))
    assertEquals(actors.head.triggers.map(_.name), List("A", "B"))
    assertEquals(actors(1).triggers, Nil)
  }

  test("atomic values parse with and without an underlying type (§4.2)") {
    val ctx = parseOk(
      """bounded-context C {
        value A = UUID;
        value B;
      }"""
    )
    val values = ctx.declarations.collect { case v: Declaration.Value => v }
    values.head.body match
      case ValueBody.Atomic(Some(u)) => assertEquals(u.name, "UUID")
      case other                     => fail(s"A should carry an underlying type, got $other")
    values(1).body match
      case ValueBody.Atomic(None) => ()
      case other                  => fail(s"B should be bare atomic, got $other")
  }

  test("an underlying type only combines with the atomic form") {
    val (tokens, _) = Lexer.tokenize("bounded-context C { value X = UUID { a: A; } }")
    val res = Parser.parse(tokens, Nil)
    assert(res.diagnostics.nonEmpty)
  }

  test("atomic, product and sum values") {
    val ctx = parseOk(
      """bounded-context C {
        value Id;
        value Money { amount: Amount; }
        value Status { Created Refunded }
      }"""
    )
    val values = ctx.declarations.collect { case v: Declaration.Value => v }
    assertEquals(values.map(_.name), List("Id", "Money", "Status"))
    assertEquals(values.head.body, ValueBody.Atomic(None))
    values(1).body match
      case ValueBody.Product(fs) =>
        assertEquals(fs.map(f => (f.name, f.fieldType.name)), List(("amount", "Amount")))
      case other => fail(s"Money should be a product, got $other")
    values(2).body match
      case ValueBody.Sum(vs) =>
        assertEquals(vs.map(_.name), List("Created", "Refunded"))
      case other => fail(s"Status should be a sum, got $other")
  }

  test("sum of products variants carry fields") {
    val ctx = parseOk(
      """bounded-context C {
        value Result { Success { id: Id; } Failure }
      }"""
    )
    ctx.declarations.head match
      case Declaration.Value(_, ValueBody.Sum(variants), _, _) =>
        assertEquals(variants.map(v => (v.name, v.fields.map(_.name))), List(("Success", List("id")), ("Failure", Nil)))
      case other => fail(s"expected sum value, got $other")
  }

  test("entity accepts @aggregate-root after the name and as a leading annotation") {
    val ctx = parseOk(
      """bounded-context C {
        entity A @aggregate-root { id: Id; }
        @aggregate-root
        entity B { id: Id; }
        entity C { id: Id; }
      }"""
    )
    val roots = ctx.declarations.collect { case e: Declaration.Entity => e.aggregateRoot }
    assertEquals(roots, List(true, true, false))
  }

  test("command with and without a body") {
    val ctx = parseOk("bounded-context C { command WithBody { id: Id; } command Empty; }")
    val commands = ctx.declarations.collect { case c: Declaration.Command => c.fields.map(_.name) }
    assertEquals(commands, List(List("id"), Nil))
  }

  test("domain-entity handlers and transitions with emits lists") {
    val ctx = parseOk(
      """bounded-context C {
        domain-entity DE on R {
          handles Cmd { on R -> Ev }
          transition Ev { from R -> R emits IE1, IE2 }
          transition Ev2 { from R -> R }
        }
      }"""
    )
    ctx.declarations.head match
      case Declaration.DomainEntity(name, on, members, _, _) =>
        assertEquals(name, "DE")
        assertEquals(on.name, "R")
        assertEquals(members.length, 3)
        members.head match
          case Member.Handler(c, trigger, s, arms, _) =>
            assertEquals((c.name, s.name), ("Cmd", "R"))
            assertEquals(trigger, None)
            assertEquals(
              arms.map(a => (a.when, a.event.map(_.name), a.fails.map(_.name))),
              List((None, Some("Ev"), Nil))
            )
          case other => fail(s"expected handler, got $other")
        members(1) match
          case Member.Transition(ev, from, to, emits, _) =>
            assertEquals((ev.name, from.name, to.name), ("Ev", "R", "R"))
            assertEquals(emits.map(_.name), List("IE1", "IE2"))
          case other => fail(s"expected transition, got $other")
      case other => fail(s"expected domain entity, got $other")
  }

  test("handler with a from clause parses its trigger (§13)") {
    val ctx = parseOk(
      """bounded-context C {
        domain-entity DE on R {
          handles Cmd from Trig { on R -> Ev }
        }
      }"""
    )
    ctx.declarations.head match
      case Declaration.DomainEntity(_, _, List(h: Member.Handler), _, _) =>
        assertEquals(h.command.name, "Cmd")
        assertEquals(h.trigger.map(_.name), Some("Trig"))
        assertEquals(h.state.name, "R")
        assertEquals(h.arms.map(a => (a.event.map(_.name), a.fails.map(_.name))), List((Some("Ev"), Nil)))
      case other => fail(s"expected handler, got $other")
  }

  test("handler with a fails clause parses its errors (§13)") {
    val ctx = parseOk(
      """bounded-context C {
        domain-entity DE on R {
          handles Cmd { on R -> Ev fails Err1, Err2 }
        }
      }"""
    )
    ctx.declarations.head match
      case Declaration.DomainEntity(_, _, List(h: Member.Handler), _, _) =>
        assertEquals(h.arms.map(_.fails.map(_.name)), List(List("Err1", "Err2")))
      case other => fail(s"expected handler, got $other")
  }

  test("handler match clause parses conditional arms (§13)") {
    val ctx = parseOk(
      """bounded-context C {
        domain-entity DE on R {
          handles Cmd from Trig {
            match R {
              case "all good" -> Ev
              case "not found" -> fails Err1
              case "stale" -> fails Err1, Err2
            }
          }
        }
      }"""
    )
    ctx.declarations.head match
      case Declaration.DomainEntity(_, _, List(h: Member.Handler), _, _) =>
        assertEquals(h.command.name, "Cmd")
        assertEquals(h.trigger.map(_.name), Some("Trig"))
        assertEquals(h.state.name, "R")
        assertEquals(
          h.arms.map(a => (a.when, a.event.map(_.name), a.fails.map(_.name))),
          List(
            (Some("all good"), Some("Ev"), Nil),
            (Some("not found"), None, List("Err1")),
            (Some("stale"), None, List("Err1", "Err2"))
          )
        )
      case other => fail(s"expected handler, got $other")
  }

  test("a match clause without arms is a parse error") {
    val (tokens, _) = Lexer.tokenize(
      """bounded-context C {
        domain-entity DE on R {
          handles Cmd { match R { } }
        }
      }"""
    )
    val res = Parser.parse(tokens, Nil)
    assert(res.diagnostics.exists(_.message.contains("at least one 'case' arm")))
  }

  test("annotations parse with their arguments") {
    val ctx = parseOk(
      """bounded-context C {
        @description("doc")
        @deprecated("old")
        @external-link("label", "https://example.com")
        @tags("a", "b")
        @deprecated
        value V;
      }"""
    )
    val anns = ctx.declarations.head.annotations
    assertEquals(anns.length, 5)
    assert(anns.head.isInstanceOf[Annotation.Description])
    assertEquals(anns(1).asInstanceOf[Annotation.Deprecated].reason, Some("old"))
    assertEquals(
      anns(2).asInstanceOf[Annotation.ExternalLink].label,
      Some("label")
    )
    assertEquals(anns(3).asInstanceOf[Annotation.Tags].values, List("a", "b"))
    assertEquals(anns(4).asInstanceOf[Annotation.Deprecated].reason, None)
  }

  test("syntax errors produce positioned diagnostics") {
    val (tokens, _) = Lexer.tokenize("bounded-context C { value X }")
    val res = Parser.parse(tokens, Nil)
    assert(res.diagnostics.nonEmpty)
    assert(res.diagnostics.head.span.line >= 1)
  }

  test("parser recovers and reports several errors") {
    val src =
      """bounded-context C {
        value;
        value Ok;
        entity;
      }"""
    val (tokens, _) = Lexer.tokenize(src)
    val res = Parser.parse(tokens, Nil)
    assertEquals(res.diagnostics.length, 2, res.diagnostics.map(_.render).mkString("\n"))
    val recovered = res.context.get.declarations.collect { case v: Declaration.Value => v.name }
    assertEquals(recovered, List("Ok"))
  }

  test("content after the bounded-context is rejected (§23.1)") {
    val (tokens, _) = Lexer.tokenize("bounded-context C { } value X;")
    val res = Parser.parse(tokens, Nil)
    assert(res.diagnostics.exists(_.message.contains("exactly one bounded-context")))
  }
