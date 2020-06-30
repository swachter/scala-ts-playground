package eu.swdev.scala.ts

import scala.collection.mutable
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolOccurrence.Role
import scala.meta.internal.semanticdb.{MethodSignature, SymbolInformation, ValueSignature}
import scala.meta.internal.symtab.SymbolTable
import scala.meta.internal.{semanticdb => isb}
import scala.meta.transversers.Traverser
import scala.meta.{Defn, Init, Lit, Mod, Name, Term, Tree, Type}
import scala.reflect.ClassTag
import scala.scalajs.js.annotation.{JSExport, JSExportAll, JSExportTopLevel}

object Analyzer {

  def classSymbol[C](implicit ev: ClassTag[C]) = s"${ev.runtimeClass.getName.replace('.', '/')}#"

  val jsExportTopLevelSymbol = classSymbol[JSExportTopLevel]
  val jsExportSymbol         = classSymbol[JSExport]
  val jsExportAllSymbol      = classSymbol[JSExportAll]

  /**
    * @return flattened list of all input definitions
    */
  def analyze(semSrc: SemSource, symTab: SymbolTable): List[Input.Defn] = {

    def existsSymbolReference(tree: Tree, symbol: String): Boolean =
      semSrc
        .symbolOccurrences(tree.pos, Role.REFERENCE)
        .exists(so => so.symbol == symbol)

    def existsTopLevelExportAnnot(tree: Tree): Boolean = existsSymbolReference(tree, jsExportTopLevelSymbol)
    def existsExportAnnot(tree: Tree): Boolean         = existsSymbolReference(tree, jsExportSymbol)
    def existsExportAllAnnot(tree: Tree): Boolean      = existsSymbolReference(tree, jsExportAllSymbol)

    val exportTopLevelAnnot: PartialFunction[Mod, ExportAnnot.TopLevel] = {
      case Mod.Annot(t @ Init(_, _, List(List(Lit.String(lit))))) if existsTopLevelExportAnnot(t) => ExportAnnot.TopLevel(lit)
    }

    val exportMemberAnnot: PartialFunction[Mod, ExportAnnot.Member] = {
      case Mod.Annot(t @ Init(_, _, List(List(Lit.String(lit))))) if existsExportAnnot(t) => ExportAnnot.MemberWithName(lit)
      case Mod.Annot(t @ Init(_, _, Nil)) if existsExportAnnot(t)                         => ExportAnnot.MemberWithoutName
    }

    val exportAnnot = exportTopLevelAnnot orElse exportMemberAnnot

    def exportName(mods: List[Mod]): Option[ExportAnnot] = mods.collect(exportAnnot).headOption

    def fieldName(mods: List[Mod], default: String): String = mods.collect(exportMemberAnnot).map {
      case ExportAnnot.MemberWithoutName => default
      case ExportAnnot.MemberWithName(s) => s
    } .headOption.getOrElse(default)

    def hasExportAllAnnot(mods: List[Mod]): Boolean = {
      mods.exists {
        case Mod.Annot(t @ Init(_, _, _)) => existsExportAllAnnot(t)
        case _                            => false
      }
    }

    def hasCaseClassMod(mods: List[Mod]): Boolean = mods.exists(_.isInstanceOf[Mod.Case])
    def hasValMod(mods: List[Mod]): Boolean       = mods.exists(_.isInstanceOf[Mod.ValParam])
    def hasVarMod(mods: List[Mod]): Boolean       = mods.exists(_.isInstanceOf[Mod.VarParam])
    def hasPrivateMod(mods: List[Mod]): Boolean   = mods.exists(_.isInstanceOf[Mod.Private])
    def hasAbstractMod(mods: List[Mod]): Boolean  = mods.exists(_.isInstanceOf[Mod.Abstract])

    def isSubtypeOfJsAny(si: SymbolInformation): Boolean = si.isSubtypeOf("scala/scalajs/js/Any#", symTab)

    val traverser = new Traverser {

      class State(expAll: Boolean) {

        val builder = List.newBuilder[Input.Defn]

        def processDefValVar[D <: Defn](defn: D,
                                        mods: List[Mod],
                                        ctor: (SemSource, D, Option[ExportAnnot], SymbolInformation) => Input.Defn): Unit =
          if (!hasPrivateMod(mods)) {
            for {
              si <- semSrc.symbolInfo(defn.pos, Kind.METHOD)
            } {
              val en = exportName(mods)
              val export = en match {
                case Some(_) => true
                case None    => expAll
              }
              if (export) {
                builder += ctor(semSrc, defn, exportName(mods), si)
              }
            }
          }

        def recurse(expAll: Boolean, visitChildren: => Unit): List[Input.Defn] = {
          states.push(new State(expAll))
          visitChildren
          states.pop().builder.result()
        }

        def processCls(defn: Defn.Class, visitChildren: => Unit): Unit = if (!hasPrivateMod(defn.mods)) {
          for {
            si <- semSrc.symbolInfo(defn.pos, Kind.CLASS)
          } {
            val ctorParamTerms = defn.ctor.paramss.flatten

            val isCaseClass          = hasCaseClassMod(defn.mods)
            val allMembersAreVisible = hasExportAllAnnot(defn.mods) || isSubtypeOfJsAny(si)

            def isCtorParamVisibleAsField(termMods: List[Mod]): Boolean = {
              !hasPrivateMod(termMods) && (allMembersAreVisible || termMods.exists(exportMemberAnnot.isDefinedAt(_)))
            }

            val ctorParams = semSrc.symbolInfo(defn.pos, Kind.CONSTRUCTOR) match {
              case Some(ctorSi) =>
                val ctorSig              = ctorSi.signature.asInstanceOf[MethodSignature]
                val ctorParamSymbolInfos = ctorSig.parameterLists.flatMap(_.symlinks.map(semSrc.symbolInfo(_)))
                ctorParamSymbolInfos.flatMap { ctorParamSi =>
                  ctorParamTerms.collect {
                    case tp @ Term.Param(termMods, termName @ Name(ctorParamSi.displayName), termType, defaultTerm)
                        if isCtorParamVisibleAsField(termMods) && hasVarMod(termMods) =>
                      val fldName = fieldName(termMods, termName.value)
                      Input.CtorParam(semSrc, tp, ctorParamSi.displayName, ctorParamSi, Input.CtorParamMod.Var(fldName))
                    case tp @ Term.Param(termMods, termName @ Name(ctorParamSi.displayName), termType, defaultTerm)
                        if isCtorParamVisibleAsField(termMods) && (isCaseClass || hasValMod(termMods)) =>
                      val fldName = fieldName(termMods, termName.value)
                      Input.CtorParam(semSrc, tp, ctorParamSi.displayName, ctorParamSi, Input.CtorParamMod.Val(fldName))
                    case tp @ Term.Param(termMods, termName, termType, defaultTerm) if termName.value == ctorParamSi.displayName =>
                      Input.CtorParam(semSrc, tp, ctorParamSi.displayName, ctorParamSi, Input.CtorParamMod.Prv)
                  }
                }.toList
              case None => Nil
            }

            val members = recurse(allMembersAreVisible, visitChildren)
            builder += Input.Cls(semSrc, defn, exportName(defn.mods), si, members, ctorParams, hasAbstractMod(defn.mods))
          }

        }

        def processObj(defn: Defn.Object, visitChildren: => Unit): Unit = if (!hasPrivateMod(defn.mods)) {
          for {
            si <- semSrc.symbolInfo(defn.pos, Kind.OBJECT)
          } {
            val members = recurse(hasExportAllAnnot(defn.mods) || isSubtypeOfJsAny(si), visitChildren)
            builder += Input.Obj(semSrc, defn, exportName(defn.mods), si, members, expAll)
          }

        }

        def processTrait(defn: Defn.Trait, visitChildren: => Unit): Unit = if (!hasPrivateMod(defn.mods)) {
          for {
            si <- semSrc.symbolInfo(defn.pos, Kind.TRAIT)
          } {
            val members = recurse(hasExportAllAnnot(defn.mods) || isSubtypeOfJsAny(si), visitChildren)
            builder += Input.Trait(semSrc, defn, si, members)
          }
        }

        def processType(defn: Defn.Type): Unit = if (!hasPrivateMod(defn.mods)) {
          for {
            si <- semSrc.symbolInfo(defn.pos, Kind.TYPE)
          } {
            val e = Input.Alias(semSrc, defn, si)
            (e.typeSignature.lowerBound.typeSymbol, e.typeSignature.upperBound.typeSymbol) match {
              case (Some(s1), Some(s2)) if s1 == s2 => builder += e
              case _                                =>
            }
          }
        }

        def process(tree: Tree, visitChildren: => Unit): Unit = tree match {
          case t: Defn.Def    => processDefValVar(t, t.mods, Input.Def)
          case t: Defn.Val    => processDefValVar(t, t.mods, Input.Val)
          case t: Defn.Var    => processDefValVar(t, t.mods, Input.Var)
          case t: Defn.Class  => processCls(t, visitChildren)
          case t: Defn.Object => processObj(t, visitChildren)
          case t: Defn.Trait  => processTrait(t, visitChildren)
          case t: Defn.Type   => processType(t)
          case _              => visitChildren
        }
      }

      val states = mutable.ArrayStack(new State(false))

      override def apply(tree: Tree): Unit = {
        states.top.process(tree, super.apply(tree))
      }

    }

    traverser.apply(semSrc.source)

    val inputs = traverser.states.top.builder.result()

    flatten(inputs)
  }

  // determine all types that are referenced in the given export item
  // -> type parameters are not considered
  def referencedTypes(e: Input, symTab: SymbolTable): List[isb.Type] = referencedTypes(e).filter(!_.isTypeParam(symTab))

  private def referencedTypes(e: Input): List[isb.Type] = e match {
    case e: Input.Def       => e.methodSignature.returnType :: parameterTypes(e)
    case e: Input.Val       => List(e.methodSignature.returnType)
    case e: Input.Var       => List(e.methodSignature.returnType)
    case e: Input.Cls       => e.member.flatMap(referencedTypes) ++ e.ctorParams.flatMap(referencedTypes)
    case e: Input.Obj       => e.member.flatMap(referencedTypes)
    case e: Input.Trait     => e.member.flatMap(referencedTypes)
    case e: Input.Alias     => Nil
    case e: Input.CtorParam => List(e.valueSignature.tpe)
  }

  private def parameterTypes(e: Input.Def): List[isb.Type] = {
    def argType(symbol: String): isb.Type = {
      val si = e.semSrc.symbolInfo(symbol)
      val vs = si.signature.asInstanceOf[ValueSignature]
      vs.tpe
    }
    e.methodSignature.parameterLists.flatMap(_.symlinks).map(argType).toList
  }

  def topLevel(is: List[Input.Defn]): List[TopLevelExport] = {
    is.collect {
      case i: Input.Exportable if i.isTopLevelExport => TopLevelExport(i.name.get.topLevelExportName.get, i)
    }
  }

  private def flatten(is: List[Input.Defn]): List[Input.Defn] = {
    val b = List.newBuilder[Input.Defn]
    def go(i: Input.Defn): Unit = {
      b += i
      i match {
        case i: Input.ClsOrObj => i.member.foreach(go)
        case i: Input.Trait    => i.member.foreach(go)
        case _                 =>
      }
    }
    is.foreach(go)
    b.result()
  }

  /**
    * @param is flattened list of all input definitions
    */
  def types(is: List[Input.Defn]): List[Input.Type] = is.collect {
    case i: Input.Type => i
  }

}
