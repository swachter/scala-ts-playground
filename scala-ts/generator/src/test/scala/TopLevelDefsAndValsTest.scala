import java.nio.file.{Path, Paths}

import org.scalatest.funsuite.AnyFunSuite

import scala.meta.Defn.Def
import scala.meta.Mod.Annot
import scala.meta.internal.semanticdb.{Locator, SymbolInformation, TextDocuments}
import scala.meta._

class TopLevelDefsAndValsTest extends AnyFunSuite {

  test("test semanticdb file") {

    val url  = getClass.getResource(s"${getClass.getSimpleName}.class")
    val uri  = url.toURI
    val path = Paths.get(uri).getParent.resolve("META-INF")

    val mapBuilder = Map.newBuilder[Path, TextDocuments]

    Locator(path) { (path, textDocuments) =>
      textDocuments.documents.foreach { td =>
        val src = dialects.Scala212(td.text).parse[Source].get
        src.traverse {

          case t @ Annot(Init(tpe, name, List(List(Lit(lit))))) => {
            {
              val pos = t.pos
              import pos._
              println(s"annotation - startLine: $startLine; startColumn: $startColumn; endLine: $endLine; endColumn: $endColumn; lit: $lit")
            }
            {
              val pos = tpe.pos
              import pos._
              println(s"type pos - startLine: $startLine; startColumn: $startColumn; endLine: $endLine; endColumn: $endColumn")
            }
            {
              val pos = name.pos
              import pos._
              println(s"name pos - startLine: $startLine; startColumn: $startColumn; endLine: $endLine; endColumn: $endColumn")
            }
          }

          case t @ Def(mods, name, typeParams, termParams, tpe, term) =>
            val pos = t.pos
            import pos._
            println(s"def - startLine: $startLine; startColumn: $startColumn; endLine: $endLine; endColumn: $endColumn; name: $name")

          case tree => println(s"tree - className: ${tree.getClass.getName}; toString: $tree")
        }
      }
      mapBuilder.+=((path, textDocuments))
    }

    val map = mapBuilder.result()

  }

  test("scala-library semancitdb") {

    val url  = getClass.getClassLoader.getResource("semanticdb/scala-library-2.12.10.jar")
    val uri  = url.toURI
    val path = Paths.get(uri)

    val mapBuilder = Map.newBuilder[Path, TextDocuments]

    val symbolMapBuilder = Map.newBuilder[String, SymbolInformation]

    Locator(path) { (path, textDocuments) =>
      mapBuilder.+=((path, textDocuments))
      textDocuments.documents.foreach { textDocument =>
        textDocument.symbols.foreach { symbolInformation =>
          symbolMapBuilder.+=((symbolInformation.symbol, symbolInformation))
        }
      }
    }

    val map       = mapBuilder.result()
    val symbolMap = symbolMapBuilder.result()

    println("finished")

  }
}
