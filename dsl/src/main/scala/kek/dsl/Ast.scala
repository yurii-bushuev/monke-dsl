package kek.dsl

/** Abstract syntax for the Domain Boundary DSL (PROPOSAL.md §2–§19). */
object Ast:

  /** Documentation and lifecycle metadata (§19). Only `AggregateRoot` carries domain semantics. */
  sealed trait Annotation:
    def span: Span

  object Annotation:
    final case class Description(text: String, span: Span)                        extends Annotation
    final case class Deprecated(reason: Option[String], span: Span)               extends Annotation
    final case class ExternalLink(label: Option[String], url: String, span: Span) extends Annotation
    final case class Tags(values: List[String], span: Span)                       extends Annotation
    final case class AggregateRoot(span: Span)                                    extends Annotation

  final case class TypeRef(name: String, span: Span)

  final case class Field(name: String, span: Span, fieldType: TypeRef)

  final case class Variant(name: String, span: Span, fields: List[Field])

  enum ValueBody:
    /** An atomic Value; `underlying` optionally names an implementation type (§4.2) — an opaque-type
      * declaration, not a domain type reference.
      */
    case Atomic(underlying: Option[TypeRef])
    case Product(fields: List[Field])
    case Sum(variants: List[Variant])

  sealed trait Member

  object Member:
    /** One outcome of the decide stage (§13 `match` arm, or the normalized `on` clause): produce the
      * Domain Event, or fail with the listed Domain Errors. `when` is the documentary condition —
      * `None` for the unconditional single-outcome `on` clause.
      */
    final case class Arm(when: Option[String], event: Option[TypeRef], fails: List[TypeRef], span: Span)

    /** `handles Command [from IntegrationEvent] { on-clause | match-clause }` (§13). `trigger` names
      * the Integration Event whose arrival issues the command; every arm names one outcome of the
      * decision — an event to produce, or errors to fail with.
      */
    final case class Handler(
        command: TypeRef,
        trigger: Option[TypeRef],
        state: TypeRef,
        arms: List[Arm],
        span: Span
    ) extends Member

    /** `transition DomainEvent { from S -> S' emits IE, ... }` (§14–§16). */
    final case class Transition(event: TypeRef, from: TypeRef, to: TypeRef, emits: List[TypeRef], span: Span)
        extends Member

  sealed trait Declaration:
    def name: String
    def span: Span
    def annotations: List[Annotation]

  object Declaration:
    final case class Value(name: String, body: ValueBody, annotations: List[Annotation], span: Span)
        extends Declaration

    final case class Entity(
        name: String,
        aggregateRoot: Boolean,
        fields: List[Field],
        annotations: List[Annotation],
        span: Span
    ) extends Declaration

    final case class Command(name: String, fields: List[Field], annotations: List[Annotation], span: Span)
        extends Declaration

    final case class DomainEvent(name: String, fields: List[Field], annotations: List[Annotation], span: Span)
        extends Declaration

    final case class IntegrationEvent(
        name: String,
        fields: List[Field],
        annotations: List[Annotation],
        span: Span
    ) extends Declaration

    final case class Error(name: String, fields: List[Field], annotations: List[Annotation], span: Span)
        extends Declaration

    final case class DomainEntity(
        name: String,
        on: TypeRef,
        members: List[Member],
        annotations: List[Annotation],
        span: Span
    ) extends Declaration

    /** An external initiator of Commands (§24): a User, Client, or External System. Not a domain
      * type — fields cannot reference it.
      */
    final case class Actor(name: String, triggers: List[TypeRef], annotations: List[Annotation], span: Span)
        extends Declaration

  final case class BoundedContext(
      name: String,
      annotations: List[Annotation],
      declarations: List[Declaration],
      span: Span
  )
