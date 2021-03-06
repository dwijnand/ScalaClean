package org.scalaclean.analysis

import java.util.concurrent.ConcurrentHashMap

/**
  * should generally be the companion of the [[ExtensionData]] case class
  *
  * @tparam T the companion
  */
abstract class ExtensionDescriptor[T <: ExtensionData] {
  def clearData = interner.clear

  private val interner = new ConcurrentHashMap[String, T]()

  def fromCsv(s: String): T = {
    interner.computeIfAbsent(s, build)
  }

  protected def build(s: String): T

}

trait ExtensionData extends Product with Ordered[ExtensionData] {
  def toCsv: String = productIterator.mkString("", ",", "")

  override def compare(that: ExtensionData): Int =
    if (this == that) 0
    else if (this.getClass != that.getClass) this.getClass.getName.compareTo(that.getClass.getName)
    else this.toCsv.compareTo(that.toCsv)
}

abstract class StandardExtensionDescriptor[T <: ExtensionData] extends ExtensionDescriptor[T] {
  protected def buildImpl(posOffsetStart: Int, posOffsetEnd: Int, otherParams: String*): T

  private def parsePos(str: String) = {
    if (str.isEmpty) Int.MinValue else str.toInt
  }

  final override protected def build(s: String): T = {
    val params: Array[String] = {
      val raw = s.split(",")
      if (s.endsWith(",")) raw.padTo(raw.size + 1, "") else raw
    }
    val start = parsePos(params(0))
    val end = parsePos(params(1))
    buildImpl(start, end, params.drop(2).map(_.intern): _*)
  }
}

trait StandardExtensionData extends ExtensionData {
  /**
    * the start offset from the element
    * Note - we use offsets to promote reuse
    */
  def posOffsetStart: Int

  /**
    * the end offset from the element
    * Note - we use offsets to promote reuse
    */
  def posOffsetEnd: Int

  def hasPosition: Boolean = posOffsetStart == Int.MinValue && posOffsetEnd == Int.MinValue

  protected def maskToString(offset: Int) = {
    if (offset == Int.MinValue) "<NoPos>" else offset.toString
  }

  protected def maskToCSV(offset: Int) = {
    if (offset == Int.MinValue) "" else offset.toString
  }

  protected def restToCSV: String

  override final def toCsv: String = s"${maskToCSV(posOffsetStart)},${maskToCSV(posOffsetEnd)}$restToCSV"

  override def toString: String = s"$productPrefix[${maskToString(posOffsetStart)},${maskToString(posOffsetEnd)},${productIterator.drop(2).mkString(",")}]"
}




