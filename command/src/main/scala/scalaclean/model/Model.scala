package scalaclean.model

import org.scalaclean.analysis.AnnotationData
import scalaclean.model.impl.ElementId

import scala.reflect.ClassTag
import org.scalaclean.analysis.ExtensionData

sealed trait ModelElement extends Ordered[ModelElement] {

  override def compare(that: ModelElement): Int = symbol.symbol.value.compare(that.symbol.symbol.value)

  def symbol: ElementId
  def modelElementId: NewElementId

  var mark: Mark = _

  def name: String

  //usually just one element. Can be >1 for  RHS of a val (a,b,c) = ...
  //where a,b,c are the enclosing
  def enclosing: List[ModelElement]

  def classOrEnclosing: ClassLike

  def annotationsOf(cls: Class[_]): Iterable[AnnotationData] =
    annotations filter (_.fqName == cls.getName)
  def annotations: Iterable[AnnotationData] =
    extensions collect {
      case a: AnnotationData => a
    }
  def extensions: Iterable[ExtensionData]

  //start target APIs
  def outgoingReferences: Iterable[Refers] = allOutgoingReferences map (_._2)

  def overrides: Iterable[Overrides] = {
    val direct = (allDirectOverrides map (_._2)).toSet
    val thisSym = symbol
    val thisModalSym = modelElementId
    allTransitiveOverrides map {
      case (_, sym, modelSym) => new impl.OverridesImpl(thisSym, thisModalSym, sym, modelSym, direct.contains(sym))
    }
  }

  //should be in the following form
  //  def outgoingReferences: Iterable[Refers]
  //  def outgoingReferencedInternal[T <: ModelElement]: Iterable[T]
  //  def outgoingReferencedExternal: Iterable[Symbol]
  //
  //  def incomingReferences: Iterable[Refers]
  //  def incomingReferencedInternal[T <: ModelElement]: Iterable[T]

  //end target APIs

  //start old APIs
  def internalOutgoingReferences: List[(ModelElement, Refers)]

  def internalIncomingReferences: List[(ModelElement, Refers)]

  def allOutgoingReferences: List[(Option[ModelElement], Refers)]

  def internalDirectOverrides: List[ModelElement]

  def internalTransitiveOverrides: List[ModelElement]

  def allDirectOverrides: List[(Option[ModelElement], ElementId, NewElementId)]

  def allTransitiveOverrides: List[(Option[ModelElement], ElementId, NewElementId)]

  def internalDirectOverriddenBy: List[ModelElement]

  def internalTransitiveOverriddenBy: List[ModelElement]

  //end old APIs

  //any block may contain many val of the same name!
  //  val foo = {
  //    if (1 == 1) {
  //      val x = 2
  //      x
  //    } else {
  //      val x = 3
  //      x
  //    }
  //  }
  def fields: List[FieldModel]

  def methods: List[MethodModel]

  def innerClassLike: Seq[ClassLike]


  protected def infoTypeName: String

  protected def infoPosString: String
  final def infoPosSorted: (String,Int,Int) = (sourceFileName, rawStart, rawEnd)
  def rawStart: Int
  def rawEnd: Int
  def sourceFileName: String

  protected def infoDetail = ""

  protected def infoName = symbol.displayName

  override def toString: String = s"$infoTypeName $infoName [$infoPosString] $infoDetail [[${symbol.symbol}]]"
}

sealed trait ClassLike extends ModelElement {
  def fullName: String

  def xtends[T](implicit cls: ClassTag[T]): Boolean

  def xtends(symbol: ElementId): Boolean

  def directExtends: Set[ElementId]

  def transitiveExtends: Set[ElementId]

  def directExtendedBy: Set[ClassLike]

  def transitiveExtendedBy: Set[ClassLike]
}

sealed trait ClassModel extends ClassLike {
  override protected final def infoTypeName: String = "ClassModel"
}

sealed trait ObjectModel extends ClassLike with FieldModel {
  def isTopLevel: Boolean = {
    enclosing.forall(_.isInstanceOf[SourceModel])
  }

  override protected final def infoTypeName: String = "ObjectModel"

  final override def otherFieldsInSameDeclaration = Nil

  type fieldType = ObjectModel
}

sealed trait TraitModel extends ClassLike {
  override protected final def infoTypeName: String = "TraitModel"
}

sealed trait MethodModel extends ModelElement
sealed trait AccessorModel extends MethodModel {
  def field : Option[FieldModel]
}
sealed trait PlainMethodModel extends MethodModel {
  override protected final def infoTypeName: String = "PlainMethodModel"
}
sealed trait GetterMethodModel extends AccessorModel {
  override protected final def infoTypeName: String = "GetterMethodModel"
}
sealed trait SetterMethodModel extends AccessorModel {
  override protected final def infoTypeName: String = "SetterMethodModel"
  def field : Option[VarModel]
}

sealed trait FieldModel extends ModelElement {
  type fieldType <: FieldModel

  def otherFieldsInSameDeclaration: Seq[fieldType]
}

sealed trait ValModel extends FieldModel {
  def isLazy: Boolean

  type fieldType = ValModel

  override protected final def infoTypeName: String = "ValModel"
}

sealed trait VarModel extends FieldModel {
  type fieldType = VarModel

  override protected final def infoTypeName: String = "VarModel"
}

sealed trait SourceModel extends ModelElement {
  type fieldType = SourceModel

  override protected final def infoTypeName: String = "SourceModel"
}

trait ProjectModel {

  def fromSymbol[T <: ModelElement](symbol: ElementId)(implicit tpe: ClassTag[T]): T

  def fromSymbolLocal[T <: ModelElement](symbol: ElementId, startPos: Int, endPos: Int)(implicit tpe: ClassTag[T]): T = {
    fromSymbol(symbol)
  }


  def getElement[T <: ModelElement](symbol: ElementId)(implicit tpe: ClassTag[T]): Option[T]

  def size: Int

  def allOf[T <: ModelElement : ClassTag]: Iterator[T]

  def printStructure() = allOf[ClassLike] foreach {
    cls => println(s"class ${cls.fullName}")
  }
}
abstract sealed class NewElementId(val id:String)


package impl {

  case class BasicElementInfo(symbol: ElementId, newElementId: NewElementId, source: SourceData,
                              startPos: Int, endPos: Int,
                              flags: Long, extensions: Seq[ExtensionData],
                              traversal: Int)

  case class BasicRelationshipInfo(
    refers: Map[ElementId, List[RefersImpl]],
    extnds: Map[ElementId, List[ExtendsImpl]],
    overrides: Map[ElementId, List[OverridesImpl]],
    within: Map[ElementId, List[WithinImpl]],
    getter: Map[ElementId, List[GetterImpl]],
    setter: Map[ElementId, List[SetterImpl]]) {
    def complete(elements: Map[ElementId, ElementModelImpl], modelElements: Map[NewElementId, ElementModelImpl]): Unit = {
      refers.values.foreach(_.foreach(_.complete(elements, modelElements)))
      extnds.values.foreach(_.foreach(_.complete(elements, modelElements)))
      overrides.values.foreach(_.foreach(_.complete(elements, modelElements)))
      within.values.foreach(_.foreach(_.complete(elements, modelElements)))
      getter.values.foreach(_.foreach(_.complete(elements, modelElements)))
      setter.values.foreach(_.foreach(_.complete(elements, modelElements)))
    }

    def byTo: BasicRelationshipInfo = {
      def byToSymbol[T <: Reference](from: Map[ElementId, List[T]]): Map[ElementId, List[T]] = {
        from.values.flatten.groupBy(_.toSymbol).map {
          case (k, v) => k -> (v.toList)
        }
      }

      BasicRelationshipInfo(
        byToSymbol(refers),
        byToSymbol(extnds),
        byToSymbol(overrides),
        byToSymbol(within),
        byToSymbol(getter),
        byToSymbol(setter)
      )

    }

    def +(that: BasicRelationshipInfo): BasicRelationshipInfo = {
      val res = BasicRelationshipInfo(
        this.refers ++ that.refers,
        this.extnds ++ that.extnds,
        this.overrides ++ that.overrides,
        this.within ++ that.within,
        this.getter ++ that.getter,
        this.setter ++ that.setter
      )
      //there should be no overlaps
      assert(res.refers.size == this.refers.size + that.refers.size)
      assert(res.extnds.size == this.extnds.size + that.extnds.size)
      assert(res.overrides.size == this.overrides.size + that.overrides.size)
      assert(res.within.size == this.within.size + that.within.size)
      assert(res.getter.size == this.getter.size + that.getter.size)
      assert(res.setter.size == this.setter.size + that.setter.size)

      res
    }
  }

  trait LegacyReferences {
    self: ElementModelImpl =>

    override def internalOutgoingReferences: List[(ModelElement, Refers)] = {
      refersTo.collect {
        case r if r.toElement.isDefined => (r.toElement.get, r)
      }
    }

    override def internalIncomingReferences: List[(ModelElement, Refers)] = {
      refersFrom.collect {
        case r => (r.fromElement, r)
      }
    }

    override def allOutgoingReferences: List[(Option[ModelElement], Refers)] = {
      refersTo map {
        r => (r.toElement, r)
      }
    }
  }

  trait LegacyOverrides {
    self: ElementModelImpl =>

    override def internalDirectOverrides: List[ModelElement] = {
      overrides collect {
        case o if o.isDirect && o.toElement.isDefined => o.toElement.get
      }
    }

    override def internalTransitiveOverrides: List[ModelElement] = {
      overrides collect {
        case o if o.toElement.isDefined => o.toElement.get
      }
    }

    override def allDirectOverrides: List[(Option[ModelElement], ElementId, NewElementId)] = {
      overrides collect {
        case o if o.isDirect => (o.toElement, o.toSymbol, o.toNewElementId)
      }
    }

    override def allTransitiveOverrides: List[(Option[ModelElement], ElementId, NewElementId)] = {
      overrides collect {
        case o => (o.toElement, o.toSymbol, o.toNewElementId)
      }
    }

    override def internalDirectOverriddenBy: List[ModelElement] = {
      overriden collect {
        case o if o.isDirect => o.fromElement
      }
    }

    override def internalTransitiveOverriddenBy: List[ModelElement] = {
      overriden map {
        _.fromElement
      }
    }
  }

  trait LegacyExtends {
    self: ClassLikeModelImpl =>

    override def directExtends: Set[ElementId] = {
      extnds.collect {
        case s if s.isDirect => s.toSymbol
      }.toSet
    }

    override def transitiveExtends: Set[ElementId] = {
      extnds.map(_.toSymbol).toSet
    }

    override def directExtendedBy: Set[ClassLike] = {
      extendedBy.collect {
        case e if e.isDirect => e.fromElement
      }.toSet
    }

    override def transitiveExtendedBy: Set[ClassLike] = {
      extendedBy.map {
        _.fromElement
      }.toSet
    }

  }

  abstract sealed class ElementModelImpl(
    info: BasicElementInfo, relationships: BasicRelationshipInfo) extends ModelElement
    with LegacyReferences with LegacyOverrides {
    def complete(
      elements: Map[ElementId, ElementModelImpl],
      modelElements: Map[NewElementId, ElementModelImpl],
      relsFrom: BasicRelationshipInfo,
      relsTo: BasicRelationshipInfo): Unit = {
      within = relsFrom.within.getOrElse(symbol, Nil) map {
        _.toElement.get.asInstanceOf[ElementModelImpl]
      }
      children = relsTo.within.getOrElse(symbol, Nil) map {
        _.fromElement.asInstanceOf[ElementModelImpl]
      }
      refersTo = relsFrom.refers.getOrElse(symbol, Nil)
      refersFrom = relsTo.refers.getOrElse(symbol, Nil)
      _overrides = relsFrom.overrides.getOrElse(symbol, Nil)
      overriden = relsTo.overrides.getOrElse(symbol, Nil)
    }

    override def extensions: Iterable[ExtensionData] = info.extensions

    val source = info.source

    def project = source.project

    def projects = project.projects

    override val symbol: ElementId = info.symbol

    override val modelElementId = info.newElementId

    override def name = symbol.displayName

    //start set by `complete`
    var within: List[ElementModelImpl] = _
    var children: List[ElementModelImpl] = _

    var refersTo: List[Refers] = _
    var refersFrom: List[Refers] = _

    var _overrides: List[Overrides] = _

    override def overrides = _overrides

    var overriden: List[Overrides] = _
    //end set by `complete`

    override def enclosing: List[ElementModelImpl] = within

    override def classOrEnclosing: ClassLike = enclosing.head.classOrEnclosing

    override def fields: List[FieldModel] = children collect {
      case f: FieldModelImpl => f
    }

    override def methods: List[MethodModel] = children collect {
      case m: MethodModel => m
    }

    override def innerClassLike: Seq[ClassLike] = children collect {
      case c: ClassLikeModelImpl => c
    }

    protected def typeName: String
    private val offsetStart = info.startPos
    private val offsetEnd = info.endPos

    override protected def infoPosString: String = {
      s"${offsetStart}-${offsetEnd}"

      // TODO Disabled as we don't want to laod the tree to work out line / column right now
      //    val pos = tree.pos
      //    s"${pos.startLine}:${pos.startColumn} - ${pos.endLine}:${pos.endColumn}"
      //
    }

    override def rawStart: Int = offsetStart

    override def rawEnd: Int = offsetEnd

    override def sourceFileName: String = source.path.toString
  }

  abstract sealed class ClassLikeModelImpl(
    info: BasicElementInfo, relationships: BasicRelationshipInfo) extends ElementModelImpl(info, relationships)
    with ClassLike with LegacyExtends {

    var extnds: List[Extends] = _
    var extendedBy: List[Extends] = _

    override def complete(
      elements: Map[ElementId, ElementModelImpl],
      modelElements: Map[NewElementId, ElementModelImpl],
      relsFrom: BasicRelationshipInfo,
      relsTo: BasicRelationshipInfo): Unit = {
      super.complete(elements,modelElements, relsFrom, relsTo)
      extnds = relsFrom.extnds.getOrElse(symbol, Nil)
      extendedBy = relsTo.extnds.getOrElse(symbol, Nil)

    }

    override def classOrEnclosing: ClassLike = this

    override def fullName: String = ???

    override def xtends[T](implicit cls: ClassTag[T]): Boolean = {
      //TODO what is the canonocal conversion?
      xtends(ElementId(cls.runtimeClass.getName.replace('.', '/') + "#"))
    }

    override def xtends(symbol: ElementId): Boolean = {
      extnds.exists(_.toSymbol == symbol)
    }
  }

  abstract sealed class FieldModelImpl(
    info: BasicElementInfo, relationships: BasicRelationshipInfo,
    val fieldName: String, val isAbstract: Boolean)
    extends ElementModelImpl(info, relationships) with FieldModel {
    override def otherFieldsInSameDeclaration: Seq[fieldType] = ???

    override def complete(elements: Map[ElementId, ElementModelImpl],
                          modelElements: Map[NewElementId, ElementModelImpl],
                          relsFrom: BasicRelationshipInfo,
                          relsTo: BasicRelationshipInfo): Unit = {
      super.complete(elements, modelElements, relsFrom, relsTo)
      relsTo.getter.get(info.symbol) match {
        case None => getter_ = None
        case Some(f :: Nil) => getter_ = Some(f.fromElement)
        case Some(error) => ???
      }
    }
    private var getter_ : Option[GetterMethodModel] = _
    def getter = getter_

  }

  class ClassModelImpl(info: BasicElementInfo, relationships: BasicRelationshipInfo)
    extends ClassLikeModelImpl(info, relationships) with ClassModel {
    override protected def typeName: String = "class"
  }

  class ObjectModelImpl(info: BasicElementInfo, relationships: BasicRelationshipInfo)
    extends ClassLikeModelImpl(info, relationships) with ObjectModel {
    override protected def typeName: String = "object"
  }

  class TraitModelImpl(info: BasicElementInfo, relationships: BasicRelationshipInfo)
    extends ClassLikeModelImpl(info, relationships) with TraitModel {
    override protected def typeName: String = "trait"
  }

  class PlainMethodModelImpl(
                              info: BasicElementInfo, relationships: BasicRelationshipInfo,
                              val methodName: String, val isAbstract: Boolean, val hasDeclaredType: Boolean)
    extends ElementModelImpl(info, relationships) with PlainMethodModel {
    override protected def typeName: String = "def"

  }
  class GetterMethodModelImpl(
                              info: BasicElementInfo, relationships: BasicRelationshipInfo,
                              val methodName: String, val isAbstract: Boolean, val hasDeclaredType: Boolean)
    extends ElementModelImpl(info, relationships) with GetterMethodModel {
    override protected def typeName: String = "def[getter]"

    override def complete(elements: Map[ElementId, ElementModelImpl],
                          modelElements: Map[NewElementId, ElementModelImpl],
                          relsFrom: BasicRelationshipInfo,
                          relsTo: BasicRelationshipInfo): Unit = {
      super.complete(elements, modelElements, relsFrom, relsTo)
      relsFrom.getter.get(info.symbol) match {
        case None => field_ = None
        case Some(f :: Nil) => field_ = f.toElement
        case Some(error) => ???
      }
    }
    private var field_ : Option[FieldModel] = _
    def field = field_
  }
  class SetterMethodModelImpl(
                              info: BasicElementInfo, relationships: BasicRelationshipInfo,
                              val methodName: String, val isAbstract: Boolean, val hasDeclaredType: Boolean)
    extends ElementModelImpl(info, relationships) with SetterMethodModel {
    override protected def typeName: String = "def[setter]"

    override def complete(elements: Map[ElementId, ElementModelImpl],
                          modelElements: Map[NewElementId, ElementModelImpl],
                          relsFrom: BasicRelationshipInfo,
                          relsTo: BasicRelationshipInfo): Unit = {
      super.complete(elements, modelElements, relsFrom, relsTo)
      relsFrom.setter.get(info.symbol) match {
        case None => field_ = None
        case Some(f :: Nil) => field_ = f.toElement
        case Some(error) => ???
      }
    }
    private var field_ : Option[VarModel] = _
    def field = field_
  }

  class ValModelImpl(
    val info: BasicElementInfo, relationships: BasicRelationshipInfo,
    fieldName: String, isAbstract: Boolean, val isLazy: Boolean)
    extends FieldModelImpl(info, relationships, fieldName, isAbstract) with ValModel {
    override protected def typeName: String = "trait"

    protected override def infoDetail = s"${super.infoDetail} lazy=$isLazy"

  }

  class VarModelImpl(
    val info: BasicElementInfo, relationships: BasicRelationshipInfo,
    fieldName: String, isAbstract: Boolean)
    extends FieldModelImpl(info, relationships, fieldName, isAbstract) with VarModel {
    override protected def typeName: String = "var"

    override def complete(elements: Map[ElementId, ElementModelImpl],
                          modelElements: Map[NewElementId, ElementModelImpl],
                          relsFrom: BasicRelationshipInfo,
                          relsTo: BasicRelationshipInfo): Unit = {
      super.complete(elements, modelElements, relsFrom, relsTo)
      relsTo.setter.get(info.symbol) match {
        case None => setter_ = None
        case Some(f :: Nil) => setter_ = Some(f.fromElement)
        case Some(error) => ???
      }
    }
    private var setter_ : Option[SetterMethodModel] = _
    def setter = setter_

  }

  class SourceModelImpl(
    val info: BasicElementInfo, relationships: BasicRelationshipInfo) extends ElementModelImpl(info, relationships) with SourceModel {

    def filename = info.source.path
    override protected def typeName: String = "source"

  }

  object NewElementIdImpl {
    import java.util.concurrent.ConcurrentHashMap
    val interned = new ConcurrentHashMap[String, NewElementIdImpl]
    def apply(id: String) = interned.computeIfAbsent(id, id => new NewElementIdImpl(id.intern()))
  }
  final class NewElementIdImpl private(id:String) extends NewElementId(id) {
    override def hashCode(): Int = id.hashCode()

    override def toString: String = s"ModelSymbol[$id]"
  }


}