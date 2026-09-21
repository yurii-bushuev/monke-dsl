package kek.dsl

class CompilerTests extends munit.FunSuite:

  test("§22 example compiles to a model with the expected declaration counts") {
    val res = Compiler.compile(Sample.source)
    assert(!res.hasErrors, res.diagnostics.map(_.render).mkString("\n"))
    val model = res.model.get
    assertEquals(model.name, "Payments")
    assertEquals(model.count(DeclKind.ValueKind), 8)
    assertEquals(model.count(DeclKind.EntityKind), 0)
    assertEquals(model.count(DeclKind.AggregateRootKind), 2)
    assertEquals(model.count(DeclKind.CommandKind), 4)
    assertEquals(model.count(DeclKind.DomainEventKind), 5)
    assertEquals(model.count(DeclKind.IntegrationEventKind), 2)
    assertEquals(model.count(DeclKind.ErrorKind), 4)
    assertEquals(model.count(DeclKind.DomainEntityKind), 2)
    assertEquals(model.count(DeclKind.ActorKind), 2)
  }

  test("underlying types surface in the model (§4.2)") {
    val model = Compiler.compile(Sample.source).model.get
    val byName = model.byKind(DeclKind.ValueKind).map(v => v.name -> v.underlying).toMap
    assertEquals(byName("PaymentId"), Some("UUID"))
    assertEquals(byName("PartnerId"), Some("String"))
    assertEquals(byName("Amount"), Some("BigDecimal"))
    assertEquals(byName("Currency"), None)
  }

  test("declarations carry their source position for go-to-definition") {
    val model = Compiler.compile(Sample.source).model.get
    val payment = model.declarations.find(_.name == "Payment").get
    assert(payment.line > 0 && payment.col > 0)
  }

  test("model edges link commands, events and integration events through the behavior") {
    val edges = Compiler.compile(Sample.source).model.get.edges
    assert(edges.contains(
      Edge.Handles("PaymentBehavior", "Payment", "WithdrawPayment", "Payment", "PaymentWithdrawn")
    ))
    assert(edges.contains(
      Edge.Transition("PaymentBehavior", "Payment", "PaymentWithdrawn", "Payment", "Payment",
        List("PaymentWithdrawalCompleted"))
    ))
    assert(edges.contains(
      Edge.Transition("PaymentBehavior", "Payment", "PaymentRefunded", "Payment", "Payment", Nil)
    ))
    assert(edges.contains(Edge.OperatesOn("PaymentBehavior", "Payment")))
    assert(edges.contains(Edge.OperatesOn("SettlementBehavior", "Settlement")))
    assert(edges.contains(Edge.Triggers("PaymentWithdrawalCompleted", "SettlePayment")))
    assert(edges.contains(Edge.Sends("BankClient", "WithdrawPayment")))
    assert(edges.contains(Edge.Sends("PaymentScheduler", "CommitPayment")))
    assert(edges.contains(Edge.Fails("WithdrawPayment", "Payment", "PaymentNotFound")))
    assert(edges.contains(Edge.Fails("WithdrawPayment", "Payment", "InvalidPaymentState")))
    assert(edges.contains(Edge.Fails("RefundPayment", "Payment", "InsufficientRefundableAmount")))
    assert(edges.contains(
      Edge.Fails("SettlePayment", "Settlement", "SettlementAlreadyExists",
        Some("settlement already exists"))
    ))
  }

  test("a branched handler produces one edge per match arm carrying its condition") {
    val edges = Compiler.compile(Sample.source).model.get.edges
    assert(edges.contains(
      Edge.Handles("SettlementBehavior", "Settlement", "SettlePayment", "Settlement",
        "PaymentSettled", Some("settlement is open"))
    ))
    assert(edges.contains(
      Edge.Handles("SettlementBehavior", "Settlement", "SettlePayment", "Settlement",
        "PaymentRejected", Some("payment amount rejected"))
    ))
    val settleOutcomes = edges.count {
      case Edge.Handles(_, _, "SettlePayment", _, _, _) => true
      case Edge.Fails("SettlePayment", _, _, _) => true
      case _ => false
    }
    assertEquals(settleOutcomes, 3)
  }

  test("entity composition becomes a Composes edge") {
    val src =
      """bounded-context C {
        value Id;
        entity Inner { id: Id; }
        entity R @aggregate-root { inner: Inner; }
      }"""
    val edges = Compiler.compile(src).model.get.edges
    assert(edges.contains(Edge.Composes("R", "Inner")))
    assert(edges.contains(Edge.FieldType("Inner", "id", "Id")))
  }

  test("errors prevent the model from being produced") {
    val res = Compiler.compile("bounded-context C { value X; value X; }")
    assert(res.hasErrors)
    assert(res.model.isEmpty)
  }

  test("parse errors are reported with positions and prevent the model") {
    val res = Compiler.compile("bounded-context C { value }")
    assert(res.hasErrors)
    assert(res.model.isEmpty)
    assert(res.errors.head.span.line == 1)
  }
