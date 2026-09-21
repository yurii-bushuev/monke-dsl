package kek.dsl

class ChecksTests extends munit.FunSuite:

  def errors(src: String): List[String] =
    Compiler.compile(src).diagnostics.filter(_.severity == Severity.Error).map(_.message)

  def clean(src: String): Unit =
    val res = Compiler.compile(src)
    assertEquals(res.diagnostics.filter(_.severity == Severity.Error), Nil,
      res.diagnostics.map(_.render).mkString("\n"))

  test("§22 example passes all checks") {
    clean(Sample.source)
  }

  test("§23.2 duplicate declaration names") {
    val es = errors("bounded-context C { value X; value X; }")
    assert(es.exists(_.contains("Duplicate declaration 'X'")))
  }

  test("§23.2 duplicate field and variant names") {
    assert(errors("bounded-context C { value V { a: X; a: X; } value X; }")
      .exists(_.contains("Duplicate field")))
    assert(errors("bounded-context C { value V { A A } }").exists(_.contains("Duplicate variant")))
  }

  test("§23.3 unresolved type reference") {
    val es = errors("bounded-context C { command Cmd { id: Missing; } }")
    assert(es.exists(_.contains("Unresolved type reference 'Missing'")))
  }

  test("§23.13 implementation types get a helpful message") {
    val es = errors("bounded-context C { value Id; command Cmd { id: String; } }")
    assert(es.exists(_.contains("not part of the DSL type system")))
  }

  test("§23.4 entity must declare at least one field") {
    val es = errors("bounded-context C { entity E { } }")
    assert(es.exists(_.contains("must declare at least one field")))
  }

  test("§23.5 @aggregate-root only on entities") {
    assert(errors("bounded-context C { @aggregate-root value V; }")
      .exists(_.contains("@aggregate-root' may only be applied")))
    assert(errors("bounded-context C { @aggregate-root command Cmd; }")
      .exists(_.contains("@aggregate-root' may only be applied")))
  }

  test("§23.6 domain-entity must operate on an aggregate root") {
    val base =
      """bounded-context C {
        value Id;
        entity Root @aggregate-root { id: Id; }
        entity Plain { id: Id; }
        domain-entity DE1 on Plain { }
      }"""
    assert(errors(base).exists(_.contains("not marked @aggregate-root")))
    val wrongKind = "bounded-context C { domain-entity DE on V { } value V; }"
    assert(errors(wrongKind).exists(_.contains("'V' must be an entity")))
  }

  test("§23.7 handler must reference a command and a domain event") {
    val src =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        event Ev { id: Id; }
        entity R @aggregate-root { id: Id; }
        domain-entity DE on R {
          handles Ev { on R -> Cmd }
        }
      }"""
    val es = errors(src)
    assert(es.exists(_.contains("'Ev' must reference a command")))
    assert(es.exists(_.contains("'Cmd' must reference a domain event")))
  }

  test("§23.8 transition must reference a domain event") {
    val src =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        entity R @aggregate-root { id: Id; }
        domain-entity DE on R {
          transition Cmd { from R -> R }
        }
      }"""
    assert(errors(src).exists(_.contains("'Cmd' must reference a domain event")))
  }

  test("§23.9 transition states must stay in the aggregate root hierarchy") {
    val src =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        event Ev { id: Id; }
        entity R @aggregate-root { id: Id; }
        entity Unrelated @aggregate-root { id: Id; }
        domain-entity DE on R {
          handles Cmd { on R -> Ev }
          transition Ev { from Unrelated -> Unrelated }
        }
      }"""
    assert(errors(src).exists(_.contains("must be the Aggregate Root 'R'")))
  }

  test("§23.9 an entity composed inside the root is a valid transition state") {
    val src =
      """bounded-context C {
        value Id;
        entity Inner { id: Id; }
        entity R @aggregate-root { inner: Inner; }
        event Ev { id: Id; }
        domain-entity DE on R {
          transition Ev { from Inner -> Inner }
        }
      }"""
    clean(src)
  }

  test("§23.10 transition output state must equal input state") {
    val src =
      """bounded-context C {
        value Id;
        entity Inner { id: Id; }
        entity R @aggregate-root { inner: Inner; }
        event Ev { id: Id; }
        domain-entity DE on R {
          transition Ev { from R -> Inner }
        }
      }"""
    assert(errors(src).exists(_.contains("must match input state")))
  }

  test("§23.11 emits must reference integration events") {
    val src =
      """bounded-context C {
        value Id;
        event Ev { id: Id; }
        entity R @aggregate-root { id: Id; }
        domain-entity DE on R {
          transition Ev { from R -> R emits Ev }
        }
      }"""
    assert(errors(src).exists(_.contains("must reference an integration event")),
      errors(src).mkString("\n"))
  }

  test("§23.12 fields must reference values or entities") {
    val src =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        event Ev { id: Cmd; }
      }"""
    assert(errors(src).exists(_.contains("must reference a value or an entity")),
      errors(src).mkString("\n"))
  }

  test("§23.15 underlying types come from the closed vocabulary") {
    clean("bounded-context C { value X = UUID; value Y = BigDecimal; value Z; }")
    assert(errors("bounded-context C { value X = UUD; }")
      .exists(_.contains("Unknown implementation type 'UUD'")))
    assert(errors("bounded-context C { value M { a: A; } value A; value X = M; }")
      .exists(_.contains("is a domain declaration")))
  }

  test("§23.16 a from clause must reference an integration event") {    val base =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        command Other { id: Id; }
        event Ev { id: Id; }
        integration-event Trig { id: Id; }
        entity R @aggregate-root { id: Id; }
        domain-entity DE on R {
          handles Cmd from Trig { on R -> Ev }
        }
      }"""
    clean(base)
    val badCommand =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        command Other { id: Id; }
        event Ev { id: Id; }
        integration-event Trig { id: Id; }
        entity R @aggregate-root { id: Id; }
        domain-entity DE on R {
          handles Cmd from Other { on R -> Ev }
        }
      }"""
    assert(errors(badCommand).exists(_.contains("'Other' must reference an integration event")))
    val badEvent = base.replace("handles Cmd from Trig", "handles Cmd from Ev")
    assert(errors(badEvent).exists(_.contains("'Ev' must reference an integration event")))
  }

  test("§23.17 triggers must reference commands") {
    val ok =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        event Ev { id: Id; }
        actor App { triggers Cmd }
      }"""
    clean(ok)
    val bad = ok.replace("triggers Cmd", "triggers Ev")
    assert(errors(bad).exists(_.contains("'Ev' must reference a command")))
  }

  test("§23.18 fails clauses must reference errors") {
    val ok =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        event Ev { id: Id; }
        error Nope;
        entity R @aggregate-root { id: Id; }
        domain-entity DE on R {
          handles Cmd { on R -> Ev fails Nope }
        }
      }"""
    clean(ok)
    val bad = ok.replace("fails Nope", "fails Cmd")
    assert(errors(bad).exists(_.contains("'Cmd' must reference an error")))
    val badEv = ok.replace("fails Nope", "fails Ev")
    assert(errors(badEv).exists(_.contains("'Ev' must reference an error")))
  }

  test("§23.7 match event arms must reference domain events") {
    val src =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        event Ev { id: Id; }
        entity R @aggregate-root { id: Id; }
        domain-entity DE on R {
          handles Cmd {
            match R {
              case "ok" -> Ev
              case "bad" -> Cmd
            }
          }
        }
      }"""
    val es = errors(src)
    assert(es.exists(_.contains("'Cmd' must reference a domain event")), es.mkString("\n"))
  }

  test("§23.18 match fail arms must reference errors") {
    val ok =
      """bounded-context C {
        value Id;
        command Cmd { id: Id; }
        event Ev { id: Id; }
        error Nope;
        entity R @aggregate-root { id: Id; }
        domain-entity DE on R {
          handles Cmd {
            match R {
              case "ok" -> Ev
              case "bad" -> fails Nope
            }
          }
        }
      }"""
    clean(ok)
    val bad = ok.replace("fails Nope", "fails Cmd")
    assert(errors(bad).exists(_.contains("'Cmd' must reference an error")),
      errors(bad).mkString("\n"))
  }
