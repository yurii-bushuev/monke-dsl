package kek.dsl

import Ast.*

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

/** Semantic validation of the §23 rules.
  *
  * V1 interpretations of the underspecified rules (agreed for this compiler):
  *   - §23.4 "Entity identity must be valid" — an entity must declare at least one field (its identity).
  *   - §23.9 "states must belong to the Aggregate Root hierarchy" — a transition state must be the
  *     aggregate root itself or an entity transitively composed inside it via entity-typed fields.
  *   - §23.10 "S' <: S" — V1 has no subtype syntax, so the output state type must equal the input type.
  */
object Checks:

  def check(context: BoundedContext): List[Diagnostic] =
    val checks = new Impl(context)
    checks.run()
    checks.diags.toList

  private final class Impl(context: BoundedContext):
    val diags: ListBuffer[Diagnostic] = mutable.ListBuffer.empty[Diagnostic]

    private val symbols = mutable.LinkedHashMap[String, Declaration]()
    for d <- context.declarations do
      if !symbols.contains(d.name) then symbols(d.name) = d // first declaration wins; duplicates reported below

    private val entities: Map[String, Declaration.Entity] =
      symbols.toMap.collect { case (n, e: Declaration.Entity) => n -> e }

    // Commonly-written implementation types (§18): called out with a helpful message instead of a bare
    // unresolved-reference error in field positions.
    private val implementationTypes = Set(
      "String", "UUID", "Long", "Int", "Integer", "Short", "Byte", "Double", "Float", "Boolean",
      "BigDecimal", "Decimal", "List", "Seq", "Set", "Map", "Option", "Try", "Either",
      "Instant", "LocalDate", "LocalDateTime", "ZonedDateTime"
    )

    // §18: the closed vocabulary allowed in an atomic Value's underlying clause (scalars only —
    // collections are meaningless without type parameters).
    private val underlyingTypes = Set(
      "String", "UUID", "Int", "Long", "BigDecimal", "Decimal",
      "Instant", "LocalDate", "LocalDateTime", "ZonedDateTime", "Boolean"
    )

    def run(): Unit =
      context.annotations.foreach(checkContextAnnotation)
      checkUnique(context.declarations.map(d => (d.name, d.span)), "declaration")
      context.declarations.foreach(checkDeclaration)

    private def error(msg: String, span: Span): Unit =
      diags += Diagnostic.error(msg, span)

    private def kindOf(d: Declaration): String = DeclKind.of(d).label

    private def checkUnique(entries: List[(String, Span)], what: String): Unit =
      val seen = mutable.LinkedHashMap[String, Span]()
      entries.foreach { (name, span) =>
        seen.get(name) match
          case Some(first) =>
            error(s"Duplicate $what '$name' (first declared at Ln ${first.line}, Col ${first.col})", span)
          case None =>
            seen += (name -> span)
      }

    private def unresolved(ref: TypeRef): Unit =
      if implementationTypes.contains(ref.name) then
        error(
          s"'${ref.name}' is not part of the DSL type system (§18, §23.13); " +
            "declare it as a domain Value instead",
          ref.span
        )
      else error(s"Unresolved type reference '${ref.name}' (§23.3)", ref.span)

    private def requireKind(ref: TypeRef, kind: String, rule: String): Unit =
      symbols.get(ref.name) match
        case None          => unresolved(ref)
        case Some(declared) =>
          val actual = kindOf(declared)
          if actual != kind then
            error(
              s"'${ref.name}' must reference ${article(kind)} $kind ($rule), " +
                s"but it is ${article(actual)} $actual",
              ref.span
            )

    private def article(kind: String): String =
      if "aeiou".contains(kind.charAt(0)) then "an" else "a"

    private def checkContextAnnotation(a: Annotation): Unit = a match
      case Annotation.AggregateRoot(span) =>
        error("'@aggregate-root' may only be applied to an entity (§23.5)", span)
      case _ => ()

    private def checkDeclaration(d: Declaration): Unit =
      d.annotations.collect { case a: Annotation.AggregateRoot => a }.foreach { a =>
        if !d.isInstanceOf[Declaration.Entity] then
          error("'@aggregate-root' may only be applied to an entity (§23.5)", a.span)
      }
      d match
        case v: Declaration.Value =>
          v.body match
            case ValueBody.Atomic(underlying) =>
              underlying.foreach(checkUnderlying(v.name))
            case ValueBody.Product(fs) => checkFields(v.name, fs)
            case ValueBody.Sum(vs) =>
              checkUnique(vs.map(x => (x.name, x.span)), "variant")
              vs.foreach(vt => checkFields(s"${v.name}.${vt.name}", vt.fields))
        case e: Declaration.Entity =>
          if e.fields.isEmpty then
            error(s"Entity '${e.name}' must declare at least one field — its identity (§23.4)", e.span)
          checkFields(e.name, e.fields)
        case c: Declaration.Command          => checkFields(c.name, c.fields)
        case ev: Declaration.DomainEvent     => checkFields(ev.name, ev.fields)
        case ie: Declaration.IntegrationEvent => checkFields(ie.name, ie.fields)
        case er: Declaration.Error           => checkFields(er.name, er.fields)
        case de: Declaration.DomainEntity    => checkDomainEntity(de)
        case a: Declaration.Actor            => a.triggers.foreach(requireKind(_, "command", "§23.17"))

    /** §23.12: fields may reference Values or Entities; §23.3: references must resolve. */
    private def checkFields(owner: String, fields: List[Field]): Unit =
      checkUnique(fields.map(f => (f.name, f.span)), s"field of '$owner'")
      fields.foreach { f =>
        symbols.get(f.fieldType.name) match
          case None => unresolved(f.fieldType)
          case Some(declared) =>
            kindOf(declared) match
              case "value" | "entity" => ()
              case other =>
                error(
                  s"Field '${f.name}' of '$owner' must reference a value or an entity (§23.12), " +
                    s"but '${f.fieldType.name}' is a $other",
                  f.fieldType.span
                )
      }

    /** §23.15: an underlying clause names an implementation type from the closed vocabulary — never a
      * domain declaration.
      */
    private def checkUnderlying(owner: String)(ref: TypeRef): Unit =
      if symbols.contains(ref.name) then
        error(
          s"'${ref.name}' is a domain declaration; the underlying type of '$owner' must be an " +
            "implementation type (§23.15)",
          ref.span
        )
      else if !underlyingTypes.contains(ref.name) then
        error(
          s"Unknown implementation type '${ref.name}' for value '$owner' (§23.15) — " +
            s"supported: ${underlyingTypes.toList.sorted.mkString(", ")}",
          ref.span
        )

    private def checkDomainEntity(de: Declaration.DomainEntity): Unit =
      symbols.get(de.on.name) match
        case None => unresolved(de.on)
        case Some(declared) =>
          declared match
            case e: Declaration.Entity =>
              if !e.aggregateRoot then
                error(
                  s"Domain Entity '${de.name}' must operate on an Aggregate Root (§23.6), " +
                    s"but '${de.on.name}' is not marked @aggregate-root",
                  de.on.span
                )
            case other =>
              error(
                s"'${de.on.name}' must be an entity marked @aggregate-root (§23.6), " +
                  s"but it is a ${kindOf(other)}",
                de.on.span
              )
      de.members.foreach {
        case Member.Handler(command, trigger, state, arms, _) =>
          requireKind(command, "command", "§23.7")
          trigger.foreach(requireKind(_, "integration event", "§23.16"))
          arms.foreach { arm =>
            arm.event.foreach(requireKind(_, "domain event", "§23.7"))
            arm.fails.foreach(requireKind(_, "error", "§23.18"))
          }
          if !symbols.contains(state.name) then unresolved(state)
        case Member.Transition(event, from, to, emits, _) =>
          requireKind(event, "domain event", "§23.8")
          checkTransitionState(de, from)
          checkTransitionState(de, to)
          if from.name != to.name then
            error(
              s"Transition output state '${to.name}' must match input state '${from.name}' " +
                "(S' <: S; V1 has no subtyping, so S' = S) (§23.10)",
              to.span
            )
          emits.foreach(requireKind(_, "integration event", "§23.11"))
      }

    private def checkTransitionState(de: Declaration.DomainEntity, ref: TypeRef): Unit =
      symbols.get(ref.name) match
        case None => unresolved(ref)
        case Some(_) =>
          val root = de.on.name
          if !hierarchy(root).contains(ref.name) then
            error(
              s"Transition state '${ref.name}' must be the Aggregate Root '$root' " +
                "or an entity composed within it (§23.9)",
              ref.span
            )

    /** The root plus every entity transitively reachable through entity-typed fields. */
    private def hierarchy(root: String): Set[String] =
      val seen = mutable.LinkedHashSet(root)
      val work = mutable.ListBuffer(root)
      while work.nonEmpty do
        val current = work.remove(work.length - 1)
        entities.get(current).foreach { entity =>
          entity.fields.foreach { f =>
            val name = f.fieldType.name
            if entities.contains(name) && !seen.contains(name) then
              seen += name
              work += name
          }
        }
      seen.toSet
