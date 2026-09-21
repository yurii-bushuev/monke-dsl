package kek.dsl

import Ast.*

enum DeclKind:
  case ValueKind, EntityKind, AggregateRootKind, CommandKind, DomainEventKind, IntegrationEventKind,
    ErrorKind, DomainEntityKind, ActorKind

  /** The declaration's kind as spoken in check messages and the workbench. */
  def label: String = this match
    case DeclKind.ValueKind            => "value"
    case DeclKind.EntityKind           => "entity"
    case DeclKind.AggregateRootKind    => "aggregate root"
    case DeclKind.CommandKind          => "command"
    case DeclKind.DomainEventKind      => "domain event"
    case DeclKind.IntegrationEventKind => "integration event"
    case DeclKind.ErrorKind            => "error"
    case DeclKind.DomainEntityKind     => "domain entity"
    case DeclKind.ActorKind            => "actor"

  /** The graph-explorer style key for the declaration's node. */
  def css: String = this match
    case DeclKind.ValueKind            => "value"
    case DeclKind.EntityKind           => "entity"
    case DeclKind.AggregateRootKind    => "agg"
    case DeclKind.CommandKind          => "cmd"
    case DeclKind.DomainEventKind      => "evt"
    case DeclKind.IntegrationEventKind => "ie"
    case DeclKind.ErrorKind            => "err"
    case DeclKind.DomainEntityKind     => "de"
    case DeclKind.ActorKind            => "actor"

object DeclKind:
  def of(d: Declaration): DeclKind = d match
    case _: Declaration.Value            => DeclKind.ValueKind
    case e: Declaration.Entity           => if e.aggregateRoot then DeclKind.AggregateRootKind else DeclKind.EntityKind
    case _: Declaration.Command          => DeclKind.CommandKind
    case _: Declaration.DomainEvent      => DeclKind.DomainEventKind
    case _: Declaration.IntegrationEvent => DeclKind.IntegrationEventKind
    case _: Declaration.Error            => DeclKind.ErrorKind
    case _: Declaration.DomainEntity     => DeclKind.DomainEntityKind
    case _: Declaration.Actor            => DeclKind.ActorKind

final case class FieldInfo(name: String, typeName: String)

final case class DeclInfo(
    kind: DeclKind,
    name: String,
    description: Option[String],
    fields: List[FieldInfo],
    variants: List[String],
    underlying: Option[String] = None,
    line: Int = 0,
    col: Int = 0
)

/** Graph-shaped view of a compiled Bounded Context: nodes (declarations) and the edges between them.
  *
  * This is the model the workbench summary renders today and the graph explorer will consume next.
  */
enum Edge:
  case OperatesOn(domainEntity: String, aggregate: String)

  /** A decide-stage outcome (§13): the behavior decides `command` in `state` and produces `event`.
    * `when` is the documentary `match` arm condition; `None` for the unconditional `on` clause.
    */
  case Handles(
      domainEntity: String,
      aggregate: String,
      command: String,
      state: String,
      event: String,
      when: Option[String] = None
  )
  case Transition(
      domainEntity: String,
      aggregate: String,
      event: String,
      from: String,
      to: String,
      emits: List[String]
  )
  case Composes(outer: String, inner: String)
  case FieldType(owner: String, field: String, targetType: String)

  /** An Integration Event whose arrival issues a Command (§13 `from` clause). */
  case Triggers(integrationEvent: String, command: String)

  /** An Actor triggering a Command (§24 `triggers` clause). */
  case Sends(actor: String, command: String)

  /** A Domain Error a command's decision can fail with (§13 `fails` clause/arm); `when` is the
    * documentary condition of the failing arm, `None` for an unconditional `fails` clause.
    */
  case Fails(command: String, aggregate: String, error: String, when: Option[String] = None)

  /** The method name this edge translates to in generated code: `decide<Command>` for handles
    * edges, `on<Event>` for transition edges, empty for edges with no method of their own. The one
    * home of the naming convention — the graph explorer labels edges with it and the code generator
    * must follow it, so neither may derive it independently.
    */
  def methodName: String = this match
    case Edge.Handles(_, _, command, _, _, _) => s"decide$command"
    case Edge.Transition(_, _, event, _, _, _) => s"on$event"
    case _                                     => ""

final case class ContextModel(name: String, declarations: List[DeclInfo], edges: List[Edge]):
  def count(kind: DeclKind): Int           = declarations.count(_.kind == kind)
  def byKind(kind: DeclKind): List[DeclInfo] = declarations.filter(_.kind == kind)

object ContextModel:

  def build(context: BoundedContext): ContextModel =
    val entityNames = context.declarations.collect { case e: Declaration.Entity => e.name }.toSet

    def info(d: Declaration): DeclInfo =
      val desc = d.annotations.collectFirst { case Annotation.Description(t, _) => t }
      def fieldInfos(fs: List[Field]) = fs.map(f => FieldInfo(f.name, f.fieldType.name))
      val at = (d.span.line, d.span.col)
      d match
        case v: Declaration.Value =>
          val (fs, vs, underlying) = v.body match
            case ValueBody.Atomic(u)   => (Nil, Nil, u.map(_.name))
            case ValueBody.Product(fs) => (fieldInfos(fs), Nil, None)
            case ValueBody.Sum(vs)     => (Nil, vs.map(_.name), None)
          DeclInfo(DeclKind.ValueKind, v.name, desc, fs, vs, underlying, at._1, at._2)
        case e: Declaration.Entity =>
          DeclInfo(DeclKind.of(e), e.name, desc, fieldInfos(e.fields), Nil, None, at._1, at._2)
        case c: Declaration.Command =>
          DeclInfo(DeclKind.of(c), c.name, desc, fieldInfos(c.fields), Nil, None, at._1, at._2)
        case ev: Declaration.DomainEvent =>
          DeclInfo(DeclKind.of(ev), ev.name, desc, fieldInfos(ev.fields), Nil, None, at._1, at._2)
        case ie: Declaration.IntegrationEvent =>
          DeclInfo(DeclKind.of(ie), ie.name, desc, fieldInfos(ie.fields), Nil, None, at._1, at._2)
        case er: Declaration.Error =>
          DeclInfo(DeclKind.of(er), er.name, desc, fieldInfos(er.fields), Nil, None, at._1, at._2)
        case de: Declaration.DomainEntity =>
          DeclInfo(DeclKind.of(de), de.name, desc, Nil, Nil, None, at._1, at._2)
        case a: Declaration.Actor =>
          DeclInfo(DeclKind.of(a), a.name, desc, Nil, Nil, None, at._1, at._2)

    def edges(d: Declaration): List[Edge] =
      d match
        case e: Declaration.Entity =>
          e.fields.map { f =>
            if entityNames.contains(f.fieldType.name) then Edge.Composes(e.name, f.fieldType.name)
            else Edge.FieldType(e.name, f.name, f.fieldType.name)
          }
        case de: Declaration.DomainEntity =>
          Edge.OperatesOn(de.name, de.on.name) :: de.members.flatMap {
            case Member.Handler(command, trigger, state, arms, _) =>
              val handleEdges = arms.collect {
                case Member.Arm(when, Some(event), _, _) =>
                  Edge.Handles(de.name, de.on.name, command.name, state.name, event.name, when)
              }
              val failEdges = arms.flatMap { arm =>
                arm.fails.map(f => Edge.Fails(command.name, de.on.name, f.name, arm.when))
              }
              handleEdges ++ trigger.map(t => Edge.Triggers(t.name, command.name)).toList ++ failEdges
            case Member.Transition(event, from, to, emits, _) =>
              List(Edge.Transition(de.name, de.on.name, event.name, from.name, to.name, emits.map(_.name)))
          }
        case v: Declaration.Value =>
          v.body match
            case ValueBody.Product(fs) =>
              fs.map(f => Edge.FieldType(v.name, f.name, f.fieldType.name))
            case _ => Nil
        case c: Declaration.Command =>
          c.fields.map(f => Edge.FieldType(c.name, f.name, f.fieldType.name))
        case ev: Declaration.DomainEvent =>
          ev.fields.map(f => Edge.FieldType(ev.name, f.name, f.fieldType.name))
        case ie: Declaration.IntegrationEvent =>
          ie.fields.map(f => Edge.FieldType(ie.name, f.name, f.fieldType.name))
        case er: Declaration.Error =>
          er.fields.map(f => Edge.FieldType(er.name, f.name, f.fieldType.name))
        case a: Declaration.Actor =>
          a.triggers.map(t => Edge.Sends(a.name, t.name))

    ContextModel(
      name = context.name,
      declarations = context.declarations.map(info),
      edges = context.declarations.flatMap(edges)
    )
