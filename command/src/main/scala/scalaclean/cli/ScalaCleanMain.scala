package scalaclean.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Paths

import scalaclean.model.ProjectModel
import scalaclean.model.v3.{Project, ProjectSet}
import scalaclean.rules.AbstractRule
import scalafix.internal.patch.PatchInternals
import scalafix.internal.reflect.ClasspathOps
import scalafix.lint.RuleDiagnostic
import scalafix.rule.RuleName
import scalafix.scalaclean.cli.DocHelper
import scalafix.testkit.DiffAssertions
import scalafix.v1.SemanticDocument

import scala.meta._
import scala.meta.internal.io.FileIO
import scala.meta.internal.symtab.SymbolTable



class ScalaCleanMain(dcOptions: SCOptions, ruleCreateFn: ProjectModel => AbstractRule) extends DiffAssertions {

  def semanticPatch(
    rule: AbstractRule,
    sdoc: SemanticDocument,
    suppress: Boolean
  ): (String, List[RuleDiagnostic]) = {
    val fixes = Some(RuleName(rule.name) -> rule.fix(sdoc)).map(Map.empty + _).getOrElse(Map.empty)
    PatchInternals.semantic(fixes, sdoc, suppress)
  }

  def run(): Unit = {

    val projectProps = dcOptions.files.map(f => Paths.get(f.toString))

    val projectSet = new ProjectSet(projectProps :_*)

    val rule = ruleCreateFn(projectSet)
    println(s"Running rule: ${rule.name}")

    rule.beforeStart()

    projectSet.projects foreach { project =>
      runRuleOnProject(rule, projectSet, project)
    }
  }

  def runRuleOnProject(rule: AbstractRule, model: ProjectModel, project: Project): Unit = {

    val symtab: SymbolTable = ClasspathOps.newSymbolTable(project.classPath)
    val classLoader = project.classloader

    println("---------------------------------------------------------------------------------------------------")

    val srcBase = AbsolutePath(project.src)
    val base = AbsolutePath(project.srcBuildBase)

    val files = project.srcFiles.toList.map(AbsolutePath(_).toRelative(srcBase))

    files.foreach { targetFile =>

      val sdoc = DocHelper.readSemanticDoc(classLoader, symtab, srcBase, base, targetFile)
      val (fixed, _) = semanticPatch(rule, sdoc, suppress = false)

      // compare results
      val tokens = fixed.tokenize.get
      val obtained = tokens.mkString

      val targetOutput = RelativePath(targetFile.toString() + ".expected")
      val outputFile = srcBase.resolve(targetOutput)
      val expected = FileIO.slurp(outputFile, StandardCharsets.UTF_8)

      val diff = DiffAssertions.compareContents(obtained, expected)
      if (diff.nonEmpty) {
        println("###########> obtained       <###########")
        println(obtained)
        println("###########> expected       <###########")
        println(expected)
        println("###########> Diff       <###########")
        println(error2message(obtained, expected))

        System.out.flush()
        System.exit(1)
      }
    }
  }

}