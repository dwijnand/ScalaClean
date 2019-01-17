/*
rules = [ Analysis , ScalaCleanDeadCodeRemover ]

*/
package scalaclean.deadcode

object TestMain {
  def main(args: Array[String]): Unit = {

    val used = UsedClass(5)
    println(used.usedMethod(true, 3))
  }

}

case class UsedClass(value: Int)  {
  def usedMethod(myBool: Boolean, y: Int) : Int = {
    y + value
  }

  def unusedMethod(z: Int): String = {
    ???
  }
}

case class UnusedClass(value: String)