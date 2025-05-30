package scala.meta.internal.metals

import java.nio.file.Path
import java.{util => ju}

import scala.collection.mutable

import scala.meta.Dialect
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.semanticdb.Scala.Descriptor
import scala.meta.internal.semanticdb.Scala.DescriptorParser
import scala.meta.internal.semanticdb.Scala.Symbols
import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.CancelChecker
import org.eclipse.{lsp4j => l}

/**
 * A symbol search visitor for `workspace/symbol`.
 *
 * - workspace symbols are converted directly to l.SymbolInformation
 * - classpath symbols are converted into "goto definition" requests,
 *   which creates files on disk, and then into l.SymbolInformation.
 */
class WorkspaceSearchVisitor(
    workspace: AbsolutePath,
    query: WorkspaceSymbolQuery,
    token: CancelChecker,
    index: GlobalSymbolIndex,
    saveClassFileToDisk: Boolean,
    resultOrdering: Ordering[AbsolutePath] = DefaultSymbolDefinitionOrdering,
)(implicit rc: ReportContext)
    extends SymbolSearchVisitor {
  private val fromWorkspace = new ju.ArrayList[l.SymbolInformation]()
  private val fromClasspath = new ju.ArrayList[l.SymbolInformation]()
  private val bufferedClasspath = new ju.ArrayList[(String, String)]()
  def allResults(): Seq[l.SymbolInformation] = {
    if (fromWorkspace.isEmpty) {
      bufferedClasspath.forEach { case (pkg, name) =>
        expandClassfile(pkg, name)
      }
    }

    fromWorkspace.sort(byNameLength)
    fromClasspath.sort(byNameLength)

    val result = new ju.ArrayList[l.SymbolInformation]()
    result.addAll(fromWorkspace)
    result.addAll(fromClasspath)

    if (!bufferedClasspath.isEmpty && fromClasspath.isEmpty) {
      val dependencies = workspace.resolve(Directories.workspaceSymbol)
      if (!dependencies.isFile) {
        dependencies.writeText(Messages.WorkspaceSymbolDependencies.title)
      }
      result.add(
        new l.SymbolInformation(
          Messages.WorkspaceSymbolDependencies.title,
          // NOTE(olafur) The "Event" symbol kind is arbitrarily picked, in VS
          // Code its icon is a yellow lightning which makes it similar but
          // distinct enough from the regular results. I tried the "File" kind
          // but found the icon in VS Code to be ugly and its white color
          // attracted too much attention.
          SymbolKind.Event,
          new l.Location(
            dependencies.toURI.toString(),
            new l.Range(new l.Position(0, 0), new l.Position(0, 0)),
          ),
        )
      )
    }
    result.asScala.toSeq
  }
  private val byNameLength = new ju.Comparator[l.SymbolInformation] {
    def compare(x: l.SymbolInformation, y: l.SymbolInformation): Int = {
      Integer.compare(x.getName().length(), y.getName().length())
    }
  }

  private val isVisited: mutable.Set[AbsolutePath] =
    mutable.Set.empty[AbsolutePath]

  private def definition(
      pkg: String,
      filename: String,
      index: GlobalSymbolIndex,
  ): Option[(AbsolutePath, Dialect)] = {
    val nme = Classfile.name(filename)
    val tpe = Symbol(Symbols.Global(pkg, Descriptor.Type(nme)))
    val forTpe = index.findFileForToplevel(tpe)
    val defs = if (forTpe.isEmpty) {
      val term = Symbol(Symbols.Global(pkg, Descriptor.Term(nme)))
      index.findFileForToplevel(term)
    } else forTpe
    defs.sortBy(_._1)(resultOrdering).headOption
  }
  override def shouldVisitPackage(pkg: String): Boolean = true
  override def visitWorkspaceSymbol(
      path: Path,
      symbol: String,
      kind: SymbolKind,
      range: l.Range,
  ): Int = {
    val (desc, owner) = DescriptorParser(symbol)
    fromWorkspace.add(
      new l.SymbolInformation(
        desc.name.value,
        kind,
        new l.Location(path.toUri.toString, range),
        owner.replace('/', '.'),
      )
    )
    1
  }
  override def visitClassfile(pkg: String, filename: String): Int = {
    if (fromWorkspace.isEmpty || query.isClasspath) {
      expandClassfile(pkg, filename)
    } else {
      bufferedClasspath.add(pkg -> filename)
      1
    }
  }
  override def isCancelled: Boolean = token.isCancelled
  private def expandClassfile(pkg: String, filename: String): Int = {
    var isHit = false
    for {
      (path, dialect) <- definition(pkg, filename, index)
      if !isVisited(path)
    } {
      isVisited += path
      val input = path.toInput
      SemanticdbDefinition.foreach(
        input,
        dialect,
        includeMembers = false,
      ) { semanticDefn =>
        if (query.matches(semanticDefn.info)) {
          val adjustedPath =
            if (saveClassFileToDisk) path.toFileOnDisk(workspace)
            else path
          val uri = adjustedPath.toURI.toString
          fromClasspath.add(semanticDefn.toLsp(uri))
          isHit = true
        }
      }
    }
    if (isHit) 1 else 0
  }
}

object DefaultSymbolDefinitionOrdering extends Ordering[AbsolutePath] {

  override def compare(x: AbsolutePath, y: AbsolutePath): Int =
    x.toURI.toString().length() - y.toURI.toString().length()

}
