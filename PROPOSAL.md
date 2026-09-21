# Domain Boundary DSL — V1

## 1. Purpose

The Domain Boundary DSL describes the domain model and behavior of a single DDD Bounded Context.

The language defines:

* Values;
* Entities and Aggregate Roots;
* Commands;
* Domain Events;
* Domain Errors;
* Integration Events;
* Domain Entities;
* Command handlers;
* Domain transitions;
* Integration Event outputs of transitions;
* documentation and lifecycle metadata.

The DSL describes domain concepts and their relationships. Implementation types, persistence, transport, databases, Outbox implementation, and executable business logic are outside the language.

---

# 2. Compilation Unit

A source file contains exactly one Bounded Context.

```ebnf
document
    = bounded-context ;
```

```ebnf
bounded-context
    = { annotation }
      "bounded-context"
      identifier
      "{"
          { declaration }
      "}"
    ;
```

Example:

```text
bounded-context Payments {

    ...

}
```

---

# 3. Declarations

```ebnf
declaration
    = value-declaration
    | entity-declaration
    | command-declaration
    | domain-event-declaration
    | integration-event-declaration
    | error-declaration
    | domain-entity-declaration
    | actor-declaration
    ;
```

All declarations may have metadata annotations unless explicitly restricted by semantic rules.

```ebnf
annotated-declaration
    = { annotation } declaration ;
```

In practice, the grammar for declarations is therefore:

```ebnf
declaration
    = { annotation }
      (
          value-declaration
        | entity-declaration
        | command-declaration
        | domain-event-declaration
        | integration-event-declaration
        | error-declaration
        | domain-entity-declaration
      )
    ;
```

---

# 4. Values

A Value represents a domain concept without identity.

A Value may be:

* atomic;
* a product;
* a sum;
* a sum of products.

There are no separate `Alias`, `Enum`, `Struct`, or `Primitive` declarations.

## 4.1 Value declaration

```ebnf
value-declaration
    = "value"
      identifier
      value-definition
    ;
```

```ebnf
value-definition
    = [ underlying-clause ] ";"
    | product-body
    | sum-body
    ;

underlying-clause
    = "=" type-name
    ;

type-name
    = identifier
    ;
```

## 4.2 Atomic Value

```text
value PaymentId;
```

An atomic Value has no fields.

An atomic Value may declare its underlying implementation type:

```text
value PaymentId = UUID;
value Amount = BigDecimal;
```

The semantic meaning is a distinct new domain type, like a Scala opaque type:

```scala
opaque type PaymentId = UUID
```

The underlying type is not an alias: a Value is never assignment-compatible with its underlying
type, and two Values over the same underlying type remain distinct domain types.

The underlying type is outside the domain type system: it cannot be used as a `type-reference`.

A bare atomic Value remains valid; its representation is deferred to the implementation.

---

## 4.3 Product Value

```ebnf
product-body
    = "{"
          { field }
      "}"
    ;
```

Example:

```text
value Money {
    amount: Amount;
    currency: Currency;
}
```

---

## 4.4 Sum Value

```ebnf
sum-body
    = "{"
          { variant }
      "}"
    ;
```

A variant may contain fields.

```ebnf
variant
    = identifier
      [ product-body ]
    ;
```

Example:

```text
value PaymentStatus {
    Created
    Withdrawn
    Committed
    Refunded
}
```

A sum of products:

```text
value PaymentResult {

    Success {
        paymentId: PaymentId;
    }

    Failure {
        error: PaymentError;
    }
}
```

---

# 5. Entities

An Entity represents a domain concept with identity.

```ebnf
entity-declaration
    = "entity"
      identifier
      [ aggregate-root-annotation ]
      product-body
    ;
```

Example:

```text
entity Payment {
    id: PaymentId;
    amount: Money;
}
```

An Entity may be an Aggregate Root:

```text
entity Payment @aggregate-root {
    id: PaymentId;
    amount: Money;
}
```

`Aggregate Root` is a semantic role of an Entity, not a separate type.

---

# 6. Entity Composition

Entity fields may reference any domain type, including other Entities and Aggregate Roots.

For example:

```text
entity Payment @aggregate-root {
    id: PaymentId;
    customer: Customer;
    settlement: Settlement;
}

entity Customer {
    id: CustomerId;
    address: Address;
}

entity Settlement @aggregate-root {
    id: SettlementId;
    amount: Money;
}
```

The DSL does not impose structural restrictions on Entity composition.

Rules governing whether such composition is desirable are linting/domain-modeling rules rather than core type-system rules.

---

# 7. Commands

A Command represents an intention to perform an operation.

```ebnf
command-declaration
    = "command"
      identifier
      [ product-body ]
    ;
```

Examples:

```text
command WithdrawPayment {
    paymentId: PaymentId;
}
```

An empty Command is valid:

```text
command RecalculateSettlement;
```

A Command is not a historical fact.

A Command is triggered only by an Actor (§24) or an Integration Event (§13).

---

# 8. Domain Events

A Domain Event represents an immutable fact that occurred inside the Bounded Context.

```ebnf
domain-event-declaration
    = "event"
      identifier
      [ product-body ]
    ;
```

Example:

```text
event PaymentWithdrawn {
    paymentId: PaymentId;
}
```

An empty Domain Event is valid:

```text
event PaymentCreated;
```

---

# 9. Integration Events

An Integration Event represents information intentionally exposed by the Bounded Context to another Aggregate Root or an external system.

```ebnf
integration-event-declaration
    = "integration-event"
      identifier
      [ product-body ]
    ;
```

Example:

```text
integration-event PaymentWithdrawalCompleted {
    paymentId: PaymentId;
}
```

Integration Events are distinct from Domain Events.

A Domain Event represents internal domain history.

An Integration Event is derived as an output of a Domain Transition.

---

# 10. Domain Errors

A Domain Error represents a failure of a domain operation.

```ebnf
error-declaration
    = "error"
      identifier
      [ product-body ]
    ;
```

Examples:

```text
error PaymentNotFound;

error InvalidPaymentState;

error InsufficientRefundableAmount {
    available: Money;
    requested: Money;
}
```

All errors belong to the single conceptual `DomainError` type.

The DSL does not distinguish between decision errors and transition errors.

---

# 11. Domain Entity

A Domain Entity defines behavior operating on an Aggregate Root.

```ebnf
domain-entity-declaration
    = "domain-entity"
      identifier
      "on"
      type-reference
      "{"
          { domain-entity-member }
      "}"
    ;
```

Example:

```text
domain-entity PaymentBehavior on Payment {

    ...
}
```

The referenced type must resolve to an Aggregate Root.

A Domain Entity does not define a new State type.

---

# 12. Domain Entity Members

```ebnf
domain-entity-member
    = command-handler
    | transition
    ;
```

---

# 13. Command Handlers

A Domain Entity can declare how it handles a Command.

```ebnf
command-handler
    = "handles"
      type-reference
      [ "from" type-reference ]
      "{"
          ( on-clause | match-clause )
      "}"
    ;

on-clause
    = "on"
      type-reference
      "->"
      type-reference
      [ fails-clause ]
    ;

match-clause
    = "match"
      type-reference
      "{"
          match-arm
          { match-arm }
      "}"
    ;

match-arm
    = "case"
      string
      "->"
      ( type-reference | fail-arm )
    ;

fail-arm
    = "fails"
      type-reference
      { "," type-reference }
    ;

fails-clause
    = "fails"
      type-reference
      { "," type-reference }
    ;
```

The `on` clause declares a single, unconditional outcome:

```text
handles WithdrawPayment {
    on Payment -> PaymentWithdrawn
    fails PaymentNotFound, InvalidPaymentState
}
```

The `fails` clause declares the Domain Errors the decision can fail with:

```text
decide :
    (Payment, WithdrawPayment)
        → Either[DomainError+, PaymentWithdrawn]
```

Every reference in a `fails` clause must resolve to an Error declaration (§23.18).

The `match` clause declares a branched decision — the outcome depends on a predicate evaluated at
decide time:

```text
handles SettlePayment from PaymentWithdrawalCompleted {
    match Settlement {
        case "settlement is open"        -> PaymentSettled
        case "payment amount rejected"   -> PaymentRejected
        case "settlement already exists" -> fails SettlementAlreadyExists
    }
}
```

The type after `match` names the Aggregate Root state the Command is decided in, exactly as `on`
does. Each `case` names one possible outcome: the string is a documentary description of the
predicate under which the arm applies — the DSL defines no expression language for predicates (§25) —
and the arm either produces the Domain Event after the arrow or fails with the Domain Errors of a
`fails` arm. At least one arm is required. The semantic meaning of the example is:

```text
decide :
    (Settlement, SettlePayment)
        → Either[DomainError+, PaymentSettled | PaymentRejected]
```

Every event arm must resolve to a Domain Event declaration (§23.7) and every reference in a `fails`
arm must resolve to an Error declaration (§23.18). The conditions themselves carry no semantics.

A handler may declare the Integration Event that triggers it:

```text
handles SettlePayment from PaymentWithdrawalCompleted {
    on Settlement -> PaymentSettled
}
```

The `from` clause names an Integration Event whose arrival causes the Command to be issued — for
example, an event produced by another Aggregate Root of the same Bounded Context and delivered via an
outbox, or an event of an external context. The command is handled exactly as any other command; the
clause documents the trigger and is semantically checked (§23.16). The trigger example means:

```text
decide :
    (Settlement, SettlePayment)
        → Either[DomainError, PaymentSettled]
```

The DSL declares the types involved; the implementation of the decision logic is outside the DSL.

The same Command may be handled by multiple Domain Entities.

---

# 14. Domain Transitions

A Domain Transition describes how a Domain Event changes Aggregate Root state.

```ebnf
transition
    = "transition"
      type-reference
      "{"
          "from"
          type-reference
          "->"
          type-reference
          [ emits-clause ]
      "}"
    ;
```

Example:

```text
transition PaymentWithdrawn {
    from Payment -> Payment
}
```

The semantic operation is:

```text
transition :
    (State, DomainEvent)
        → Either[
            DomainError,
            (State, IntegrationEvent*)
        ]
```

---

# 15. State Evolution

A transition operates on a concrete Aggregate Root state and may produce the same state type or a subtype of it.

Conceptually:

```text
S' <: S
```

For example:

```text
transition PaymentWithdrawn {
    from CreatedPayment -> WithdrawnPayment
}
```

is valid if:

```text
WithdrawnPayment <: CreatedPayment
```

A transition cannot produce an unrelated domain type.

The exact representation of state subtyping is a semantic/compiler concern rather than an EBNF concern.

---

# 16. Transition Integration Event Output

A transition may declare the Integration Events it can produce.

```ebnf
emits-clause
    = "emits"
      type-reference
      { "," type-reference }
    ;
```

Example:

```text
transition PaymentWithdrawn {
    from Payment -> Payment
    emits PaymentWithdrawalCompleted
}
```

Multiple events:

```text
transition PaymentCommitted {
    from Payment -> Payment
    emits
        PaymentCommittedForSettlement,
        PaymentCommittedForNotification
}
```

The declared events must be Integration Events.

The Integration Events are outputs of the transition itself.

They are not global mappings from Domain Events.

Therefore:

```text
transition PaymentCommitted {
    from Payment -> Payment
    emits PaymentCommittedForSettlement
}
```

means that this particular transition can produce `PaymentCommittedForSettlement`.

---

# 17. Fields

Fields are used by Values, Entities, Commands, Domain Events, Integration Events, and Errors.

```ebnf
field
    = identifier
      ":"
      type-reference
      ";"
    ;
```

Example:

```text
amount: Money;
```

A field references a domain type.

---

# 18. Type References

```ebnf
type-reference
    = identifier ;
```

V1 does not contain implementation types such as:

```text
String
UUID
Long
Decimal
List
Option
Map
```

Implementation type names are not domain types: they cannot be used as `type-reference`s, and a
field type naming one does not resolve.

The single place an implementation type may appear is the underlying clause of an atomic Value (§4.2):

```text
value PaymentId = UUID;
```

The compiler recognizes this closed vocabulary of implementation types:

```text
String UUID Int Long BigDecimal Decimal
Instant LocalDate LocalDateTime ZonedDateTime Boolean
```

For example:

```text
value PaymentId = UUID;

entity Payment {
    id: PaymentId;
}
```

The implementation representation of a bare atomic Value is outside the DSL.

---

# 19. Metadata

The DSL supports common documentation and lifecycle metadata.

```ebnf
annotation
    = description-annotation
    | deprecated-annotation
    | external-link-annotation
    | tags-annotation
    | aggregate-root-annotation
    ;
```

---

## 19.1 Description

```ebnf
description-annotation
    = "@description"
      "("
      string
      ")"
    ;
```

Example:

```text
@description("Represents a monetary amount.")
value Money {
    amount: Amount;
    currency: Currency;
}
```

Descriptions are documentary and have no domain semantics.

---

## 19.2 Deprecation

```ebnf
deprecated-annotation
    = "@deprecated"
      [ "(" string ")" ]
    ;
```

Examples:

```text
@deprecated
value LegacyPaymentId;
```

```text
@deprecated("Use PaymentId instead.")
value LegacyPaymentId;
```

Deprecation is metadata and does not change the domain type semantics.

---

## 19.3 External Links

```ebnf
external-link-annotation
    = "@external-link"
      "("
          string
          [ "," string ]
      ")"
    ;
```

The two strings are:

```text
[label, url]
```

Example:

```text
@external-link(
    "domain-docs",
    "https://wiki.example.com/payments"
)
entity Payment @aggregate-root {
    id: PaymentId;
}
```

A link without a label is also valid:

```text
@external-link("https://wiki.example.com/payments")
```

External links are documentary metadata.

---

## 19.4 Tags

```ebnf
tags-annotation
    = "@tags"
      "("
          string
          { "," string }
      ")"
    ;
```

Example:

```text
@tags("payment", "settlement", "external")
entity Payment @aggregate-root {
    id: PaymentId;
}
```

Tags have no domain semantics.

---

## 19.5 Aggregate Root

```ebnf
aggregate-root-annotation
    = "@aggregate-root" ;
```

Example:

```text
entity Payment @aggregate-root {
    id: PaymentId;
}
```

Unlike the other annotations, `@aggregate-root` has domain semantics.

It may only be applied to an Entity.

---

# 20. Lexical Grammar

## 20.1 Identifier

```ebnf
identifier
    = identifier-start
      { identifier-part }
    ;

identifier-start
    = letter
    | "_"
    ;

identifier-part
    = letter
    | digit
    | "_"
    ;
```

---

## 20.2 Digits

```ebnf
digit
    = "0"
    | "1"
    | "2"
    | "3"
    | "4"
    | "5"
    | "6"
    | "7"
    | "8"
    | "9"
    ;
```

---

## 20.3 Letters

```ebnf
letter
    = "A" | "B" | "C" | "D" | "E" | "F" | "G"
    | "H" | "I" | "J" | "K" | "L" | "M" | "N"
    | "O" | "P" | "Q" | "R" | "S" | "T" | "U"
    | "V" | "W" | "X" | "Y" | "Z"
    | "a" | "b" | "c" | "d" | "e" | "f" | "g"
    | "h" | "i" | "j" | "k" | "l" | "m" | "n"
    | "o" | "p" | "q" | "r" | "s" | "t" | "u"
    | "v" | "w" | "x" | "y" | "z"
    ;
```

---

## 20.4 Strings

```ebnf
string
    = '"'
      { string-character }
      '"'
    ;
```

`string-character` includes all characters allowed inside a quoted string, with `"` and `\` escaped according to the implementation's string-literal rules.

---

# 21. Comments

The language supports line comments:

```text
// This is a comment
```

and block comments:

```text
/*
   This is a block comment.
*/
```

Lexically:

```ebnf
line-comment
    = "//" { any-character-except-newline } newline
    ;

block-comment
    = "/*"
      { any-character }
      "*/"
    ;
```

Comments have no semantic meaning.

---

# 22. Complete Example

```text
bounded-context Payments {

    @description("Unique identifier of a payment.")
    value PaymentId = UUID;

    value PartnerId = String;

    value Amount = BigDecimal;

    value Currency;

    @description("Monetary amount in a specific currency.")
    @external-link(
        "domain-docs",
        "https://wiki.example.com/money"
    )
    value Money {
        amount: Amount;
        currency: Currency;
    }

    value PaymentStatus {
        Created
        Withdrawn
        Committed
        Refunded
    }

    value SettlementId = UUID;

    value SettlementStatus {
        Pending
        Settled
    }


    @description("Payment aggregate.")
    @tags("payment", "core")
    entity Payment @aggregate-root {
        id: PaymentId;
        partnerId: PartnerId;
        amount: Money;
        status: PaymentStatus;
    }

    @description("Settlement aggregate; settled by a payment withdrawal reported via integration event.")
    entity Settlement @aggregate-root {
        id: SettlementId;
        paymentId: PaymentId;
        amount: Money;
        status: SettlementStatus;
    }


    command WithdrawPayment {
        paymentId: PaymentId;
    }

    command CommitPayment {
        paymentId: PaymentId;
    }

    command RefundPayment {
        paymentId: PaymentId;
        amount: Money;
    }

    command SettlePayment {
        paymentId: PaymentId;
    }


    event PaymentWithdrawn {
        paymentId: PaymentId;
    }

    event PaymentCommitted {
        paymentId: PaymentId;
    }

    event PaymentRefunded {
        paymentId: PaymentId;
        amount: Money;
    }

    event PaymentSettled {
        paymentId: PaymentId;
    }

    event PaymentRejected {
        paymentId: PaymentId;
    }


    integration-event PaymentWithdrawalCompleted {
        paymentId: PaymentId;
    }

    integration-event PaymentCommitCompleted {
        paymentId: PaymentId;
    }


    error PaymentNotFound;

    error InvalidPaymentState;

    error InsufficientRefundableAmount {
        available: Money;
        requested: Money;
    }

    error SettlementAlreadyExists;


    @description("Bank client initiating payment operations.")
    @tags("external", "client")
    actor BankClient {
        triggers WithdrawPayment
        triggers RefundPayment
    }

    @description("Backend scheduler committing payments in due time.")
    actor PaymentScheduler {
        triggers CommitPayment
    }


    domain-entity PaymentBehavior on Payment {

        handles WithdrawPayment {
            on Payment -> PaymentWithdrawn
            fails PaymentNotFound, InvalidPaymentState
        }

        handles CommitPayment {
            on Payment -> PaymentCommitted
        }

        handles RefundPayment {
            on Payment -> PaymentRefunded
            fails InsufficientRefundableAmount
        }


        transition PaymentWithdrawn {
            from Payment -> Payment
            emits PaymentWithdrawalCompleted
        }

        transition PaymentCommitted {
            from Payment -> Payment
            emits PaymentCommitCompleted
        }

        transition PaymentRefunded {
            from Payment -> Payment
        }
    }

    domain-entity SettlementBehavior on Settlement {

        handles SettlePayment from PaymentWithdrawalCompleted {
            match Settlement {
                case "settlement is open"        -> PaymentSettled
                case "payment amount rejected"   -> PaymentRejected
                case "settlement already exists" -> fails SettlementAlreadyExists
            }
        }

        transition PaymentSettled {
            from Settlement -> Settlement
        }

        transition PaymentRejected {
            from Settlement -> Settlement
        }
    }
}
```

# 23. Semantic Rules

The EBNF defines syntax only. The compiler performs semantic validation after parsing.

The minimum V1 rules are:

1. There is exactly one `bounded-context` per source file.
2. Declaration names are unique within their applicable namespace.
3. All `type-reference`s must resolve.
4. Entity identity must be valid.
5. `@aggregate-root` can only be applied to an Entity.
6. A `domain-entity` must reference an Aggregate Root.
7. A command handler must reference a Command, and every event arm must reference a Domain Event.
8. A transition must reference a Domain Event.
9. Transition input and output states must belong to the Domain Entity's Aggregate Root hierarchy.
10. Transition output state must satisfy `S' <: S`.
11. Every type in an `emits` clause must be an Integration Event.
12. Fields may reference Values or Entities, including nested Entities and Aggregate Roots.
13. Implementation types are not part of the DSL type system.
14. Metadata does not affect domain semantics, except `@aggregate-root`.
15. An underlying clause must name a known implementation type (§18); implementation type names cannot appear as `type-reference`s.
16. A `from` clause in a command handler must reference an Integration Event (§13).
17. A `triggers` clause must reference a Command; a Command can be triggered only by an Actor (§24) or an Integration Event (§13 `from` clause).
18. Every reference in a `fails` clause must resolve to an Error declaration (§13).

---

# 24. Actors

An Actor is an external initiator of Commands: a User, a Client application, or an External System.
Actors are outside the domain model — they are not types and cannot be referenced by fields.

```ebnf
actor-declaration
    = "actor"
      identifier
      [ actor-body ]
    ;

actor-body
    = "{"
          { trigger-clause }
      "}"
    ;

trigger-clause
    = "triggers"
      type-reference
    ;
```

An Actor declares the Commands it can trigger:

```text
actor BankClient {
    triggers WithdrawPayment
    triggers RefundPayment
}
```

An Actor without a body triggers nothing:

```text
actor AuditLogger;
```

Actors carry documentary metadata — `@description`, `@tags`, `@external-link` (§19). The referenced
Commands must resolve, and a `triggers` clause must name a Command (§23.17).

---

# 25. Deliberately Out of Scope

V1 does not define:

* JVM/Scala types;
* primitive types;
* collection types;
* optionality/cardinality;
* persistence;
* Event Store;
* Outbox implementation;
* Kafka or other transports;
* repositories;
* databases;
* serialization;
* projections;
* event versioning;
* migrations;
* executable business logic;
* executable invariant expressions.

The runtime may represent a successful transition using an envelope such as:

```scala
case class Envelope[DE, S, IE](
    id: Id,
    version: Int,
    domainEvent: DE,
    state: S,
    events: Seq[IE]
)
```

but this is an implementation concern rather than DSL syntax.