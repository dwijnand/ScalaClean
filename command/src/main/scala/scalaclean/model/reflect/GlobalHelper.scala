package scalaclean.model.reflect


import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.HashMap

import scala.collection.mutable
import scala.{meta => m}
import scala.meta.internal.inputs._
import scala.meta.internal.io.FileIO
import scala.meta.internal.scalacp._
import scala.meta.internal.{semanticdb => s}
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.Scala.{Descriptor => d}
import scala.meta.internal.semanticdb.Scala.{Names => n}
import scala.reflect.internal.{Flags => gf}
import scala.reflect.internal.util.{NoSourceFile => GNoSourceFile, SourceFile => GSourceFile}
import scala.reflect.io.VirtualFile
import scala.reflect.runtime.JavaUniverse
import scala.util.control.NonFatal

class GlobalHelper(g: JavaUniverse) {
  def gSymToMSymString(sym: JavaUniverse#Symbol): String = {
    XtensionGSymbolMSymbol(sym.asInstanceOf[g.Symbol]).toSemantic
  }

  private lazy val symbolCache = new HashMap[g.Symbol, String]
  implicit class XtensionGSymbolMSymbol(sym: g.Symbol) {
    def toSemantic: String = {
      def uncached(sym: g.Symbol): String = {
        if (sym == null || sym == g.NoSymbol) return Symbols.None
        if (isOverloaded(sym)) return Symbols.Multi(sym.alternatives.map(_.toSemantic))
        if (sym.isModuleClass) return sym.asClass.module.toSemantic
        if (sym.isTypeSkolem) return sym.deSkolemize.toSemantic
        if (sym.isSemanticdbLocal) return freshSymbol(sym)

        val owner = sym.owner.toSemantic
        val desc = {
          if (sym.isValMethod) {
            d.Term(sym.symbolName)
          } else if (sym.isMethod || sym.isUsefulField) {
            d.Method(sym.symbolName, sym.disambiguator)
          } else if (sym.isTypeParameter) {
            d.TypeParameter(sym.symbolName)
          } else if (sym.isValueParameter) {
            d.Parameter(sym.symbolName)
          } else if (sym.isType || sym.isJavaClass) {
            d.Type(sym.symbolName)
          } else if (sym.hasPackageFlag) {
            d.Package(sym.symbolName)
          } else {
            d.Term(sym.symbolName)
          }
        }
        Symbols.Global(owner, desc)
      }
      val msym = symbolCache.get(sym)
      if (msym != null) {
        msym
      } else {
//        val msym = try {
//          uncached(sym)
//        } catch {
//          case NonFatal(e) if isInteractiveCompiler =>
//            // happens regularly for broken code with the pc, see
//            // https://github.com/scalameta/scalameta/issues/1194
//            Symbols.None
//        }
        val msym = uncached(sym)
        symbolCache.put(sym, msym)
        msym
      }
    }
    def isOverloaded(sym: g.Symbol): Boolean = {
      sym.alternatives match {
        case head :: Nil => head ne sym
        case _ => true
      }

    }
  }

  implicit class XtensionGSymbolMSpec(sym: g.Symbol) {
    def isSemanticdbGlobal: Boolean = !isSemanticdbLocal
    def isSemanticdbLocal: Boolean = {
      def definitelyGlobal = sym.hasPackageFlag
      def definitelyLocal =
        sym == g.NoSymbol ||
          (sym.owner.isTerm && !sym.isParameter) ||
          ((sym.owner.isAliasType || sym.owner.isAbstractType) && !sym.isParameter) ||
          sym.isSelfParameter ||
          sym.isLocalDummy ||
          sym.isRefinementClass ||
          sym.isAnonymousClass ||
          sym.isAnonymousFunction ||
          sym.isExistential
      def ownerLocal = sym.owner.isSemanticdbLocal
      !definitelyGlobal && (definitelyLocal || ownerLocal)
    }
    def isSemanticdbMulti: Boolean = sym.isOverloaded
    def symbolName: String = {
      if (sym.name == g.nme.ROOTPKG) n.RootPackage.value
      else if (sym.name == g.nme.EMPTY_PACKAGE_NAME) n.EmptyPackage.value
      else if (sym.name == g.nme.CONSTRUCTOR) n.Constructor.value
      else sym.name.decoded.stripSuffix(g.nme.LOCAL_SUFFIX_STRING)
    }
    def disambiguator: String = {
      val peers = sym.owner.semanticdbDecls.gsyms
      val overloads = peers.filter { peer =>
        peer.isMethod &&
          peer.symbolName == sym.symbolName &&
          !peer.isValMethod
      }
      val suffix = {
        if (overloads.lengthCompare(1) == 0) ""
        else {
          val index = overloads.indexOf(sym)
          if (index <= 0) ""
          else "+" + index
        }
      }
      "(" + suffix + ")"
    }
    private[GlobalHelper] def semanticdbDecls: SemanticdbDecls = {
      if (sym.hasPackageFlag) {
        SemanticdbDecls(Nil)
      } else if (sym.isModule) {
        if (sym.isJavaDefined) sym.companionClass.semanticdbDecls
        else sym.moduleClass.semanticdbDecls
      } else {
        if (sym.isModuleClass && sym.isJavaDefined) {
          sym.companionClass.semanticdbDecls
        } else {
          def loop(info: g.Type): SemanticdbDecls = {
            info match {
              case g.PolyType(_, info) =>
                loop(info.asInstanceOf[g.Type])
              case g.ClassInfoType(_, gdecls, _) =>
                val gbuf = List.newBuilder[g.Symbol]
                gdecls.asInstanceOf[g.Scope].sorted.filter(_.isUseful).foreach(gbuf.+=)
                if (sym.isJavaDefined) {
                  sym.companionModule.info.decls.filter(_.isUseful).foreach(gbuf.+=)
                }
                SemanticdbDecls(gbuf.result)
              case _ =>
                SemanticdbDecls(Nil)
            }
          }
          loop(sym.info)
        }
      }
    }
  }

  implicit class XtensionGScopeMSpec(scope: g.Scope) {
    private[GlobalHelper] def semanticdbDecls: SemanticdbDecls = {
      SemanticdbDecls(scope.sorted.filter(_.isUseful))
    }
  }

//  implicit class XtensionGSymbolsMSpec(syms: List[g.Symbol]) {
//    def sscope(linkMode: LinkMode): s.Scope = {
//      linkMode match {
//        case SymlinkChildren =>
//          s.Scope(symlinks = syms.map(_.ssym))
//        case HardlinkChildren =>
//          s.Scope(hardlinks = syms.map(_.toSymbolInformation(HardlinkChildren)))
//      }
//    }
//  }

  private case class SemanticdbDecls(gsyms: List[g.Symbol]) {
//    def sscope(linkMode: LinkMode): s.Scope = {
//      linkMode match {
//        case SymlinkChildren =>
//          val sbuf = List.newBuilder[String]
//          gsyms.foreach { gsym =>
//            val ssym = gsym.ssym
//            sbuf += ssym
//            if (gsym.isUsefulField && gsym.isMutable) {
//              if (ssym.isGlobal) {
//                val setterSymbolName = ssym.desc.name + "_="
//                val setterSym = Symbols.Global(ssym.owner, d.Method(setterSymbolName, "()"))
//                sbuf += setterSym
//              } else {
//                val setterSym = ssym + "+1"
//                sbuf += setterSym
//              }
//            }
//          }
//          s.Scope(symlinks = sbuf.result)
//        case HardlinkChildren =>
//          val sbuf = List.newBuilder[s.SymbolInformation]
//          gsyms.foreach { gsym =>
//            val sinfo = gsym.toSymbolInformation(HardlinkChildren)
//            sbuf += sinfo
//            if (gsym.isUsefulField && gsym.isMutable) {
//              Synthetics.setterInfos(sinfo, HardlinkChildren).foreach(sbuf.+=)
//            }
//          }
//          s.Scope(hardlinks = sbuf.result)
//      }
//    }
  }

  private implicit class XtensionGSymbol(sym: g.Symbol) {
    def ssym: String = sym.toSemantic
    def self: g.Type = {
      sym.thisSym.info match {
        case g.RefinedType(List(_, self), _) if sym.thisSym != sym => self.asInstanceOf[g.Type]
        case _ => g.NoType
      }
    }
    def isSelfParameter: Boolean = {
      sym != g.NoSymbol && sym.owner.thisSym == sym
    }
    def isJavaClass: Boolean =
      sym.isJavaDefined &&
        !sym.hasPackageFlag &&
        (sym.isClass || sym.isModule)
    def isSyntheticConstructor: Boolean = {
      val isModuleConstructor = sym.isConstructor && sym.owner.isModuleClass
      val isTraitConstructor = sym.isMixinConstructor
      val isInterfaceConstructor = sym.isConstructor && sym.owner.isJavaDefined && sym.owner.isInterface
      val isEnumConstructor = sym.isConstructor && sym.owner.hasJavaEnumFlag
      val isStaticConstructor = sym.name == g.TermName("<clinit>")
      val isClassfileAnnotationConstructor = sym.owner.isClassfileAnnotation
      isModuleConstructor || isTraitConstructor || isInterfaceConstructor ||
        isEnumConstructor || isStaticConstructor || isClassfileAnnotationConstructor
    }
    def isLocalChild: Boolean =
      sym.name == g.tpnme.LOCAL_CHILD
    def isSyntheticValueClassCompanion: Boolean = {
      if (sym.isModule) {
        sym.moduleClass.isSyntheticValueClassCompanion
      } else {
        sym.isModuleClass &&
          sym.isSynthetic &&
          sym.semanticdbDecls.gsyms.isEmpty
      }
    }
    private def isMethod(gsym: g.Symbol): Boolean = {

      import scala.meta.internal.semanticdb.SymbolInformation.{Property => p}
      import scala.meta.internal.semanticdb.SymbolInformation.{Kind => k}
      import g._

      gsym match {
        case _ if gsym.isSelfParameter =>
        case gsym: MethodSymbol =>
          if (gsym.isConstructor) k.CONSTRUCTOR
          else if (gsym.isMacro) k.MACRO
          else if (gsym.isGetter && gsym.isLazy && gsym.isLocalToBlock) k.LOCAL
          else return true
        case gsym: ModuleSymbol =>
        case gsym: TermSymbol =>
          if (gsym.isParameter) k.PARAMETER
          else if (gsym.isLocalToBlock) k.LOCAL
          else if (gsym.isJavaDefined || gsym.hasJavaEnumFlag) k.FIELD
          else return true
        case _ =>

      }
      return false
    }
    def isValMethod: Boolean = {
      isMethod(sym) && {
        (sym.isAccessor && sym.isStable) ||
          (sym.isUsefulField && !sym.isMutable)
      }
    }
    def isScalacField: Boolean = {
      val isFieldForPrivateThis = sym.isPrivateThis && sym.isTerm && !sym.isMethod && !sym.isModule
      val isFieldForOther = sym.name.endsWith(g.nme.LOCAL_SUFFIX_STRING)
      val isJavaDefined = sym.isJavaDefined || sym.hasJavaEnumFlag
      (isFieldForPrivateThis || isFieldForOther) && !isJavaDefined
    }
    def isUselessField: Boolean = {
      sym.isScalacField && sym.getterIn(sym.owner) != g.NoSymbol
    }
    def isUsefulField: Boolean = {
      sym.isScalacField && !sym.isUselessField
    }
    def isSyntheticCaseAccessor: Boolean = {
      sym.isCaseAccessor && sym.name.toString.contains("$")
    }
    def isSyntheticJavaModule: Boolean = {
      !sym.hasPackageFlag && sym.isJavaDefined && sym.isModule
    }
    def isAnonymousClassConstructor: Boolean = {
      sym.isConstructor && sym.owner.isAnonymousClass
    }
    def isSyntheticAbstractType: Boolean = {
      sym.isSynthetic && sym.isAbstractType // these are hardlinked to TypeOps
    }
    def isEtaExpandedParameter: Boolean = {
      // Term.Placeholder occurrences are not persisted so we don't persist their symbol information.
      // We might want to revisit this decision https://github.com/scalameta/scalameta/issues/1657
      sym.isParameter &&
        sym.name.startsWith(g.nme.FRESH_TERM_NAME_PREFIX) &&
        sym.owner.isAnonymousFunction
    }
    def isAnonymousSelfParameter: Boolean = {
      sym.isSelfParameter && {
        sym.name == g.nme.this_ || // hardlinked in ClassSignature.self
          sym.name.startsWith(g.nme.FRESH_TERM_NAME_PREFIX) // wildcards can't be referenced: class A { _: B => }
      }
    }
    def isUseless: Boolean = {
      sym == g.NoSymbol ||
        sym.isAnonymousClass ||
        sym.isSyntheticConstructor ||
        sym.isStaticConstructor ||
        sym.isLocalChild ||
        sym.isSyntheticValueClassCompanion ||
        sym.isUselessField ||
        sym.isSyntheticCaseAccessor ||
        sym.isRefinementClass ||
        sym.isSyntheticJavaModule
    }
    def isUseful: Boolean = !sym.isUseless
    def isUselessOccurrence: Boolean = {
      sym.isUseless &&
        !sym.isSyntheticJavaModule // references to static Java inner classes should have occurrences
    }
    def isUselessSymbolInformation: Boolean = {
      sym.isUseless ||
        sym.isEtaExpandedParameter ||
        sym.isAnonymousClassConstructor ||
        sym.isSyntheticAbstractType ||
        sym.isAnonymousSelfParameter
    }
    def isClassfileAnnotation: Boolean = {
      sym.isClass && sym.hasFlag(gf.JAVA_ANNOTATION)
    }
    def isDefaultParameter: Boolean = {
      sym.hasFlag(gf.DEFAULTPARAM) && sym.hasFlag(gf.PARAM)
    }
    def isDefaultMethod: Boolean = {
      sym.isJavaDefined && sym.owner.isInterface && !sym.isDeferred && !sym.isStatic
    }
  }


//  lazy val idCache = new HashMap[String, Int]
//  lazy val gSourceFileInputCache = mutable.Map[GSourceFile, m.Input]()
//
//  private def freshSymbol(sym: g.Symbol, config: SemanticdbConfig): String = {
//    import scala.reflect.io.{PlainFile => GPlainFile}
//
//    def toInput(gsource: GSourceFile): m.Input =
//      gSourceFileInputCache.getOrElseUpdate(gsource, {
//        gsource.file match {
//          case gfile: GPlainFile =>
//            if (config.text.isOn) {
//              val path = m.AbsolutePath(gfile.file)
//              val label = config.sourceroot.toURI.relativize(path.toURI).toString
//              // NOTE: Can't use gsource.content because it's preprocessed by scalac.
//              val contents = FileIO.slurp(path, UTF_8)
//              m.Input.VirtualFile(label, contents)
//            } else {
//              m.Input.File(gfile.file)
//            }
//          case gfile: VirtualFile =>
//            val uri = URLEncoder.encode(gfile.path, UTF_8.name)
//            m.Input.VirtualFile(uri, gsource.content.mkString)
//          case _ =>
//            m.Input.None
//        }
//      })
//    def loop(sym: g.Symbol): GSourceFile = {
//      if (sym.pos.source != GNoSourceFile) {
//        sym.pos.source
//      } else {
//        if (sym == g.NoSymbol) GNoSourceFile
//        else loop(sym.owner)
//      }
//    }
//    val minput = toInput(loop(sym))
//    if (minput == m.Input.None) Symbols.None
//    else {
//      val id = idCache.get(minput.syntax)
//      idCache.put(minput.syntax, id + 1)
//      Symbols.Local(id.toString)
//    }
//  }
  var lastId = 0
  private def freshSymbol(sym: g.Symbol): String = {
    lastId += 1
    s"__mike__$lastId"
  }
}