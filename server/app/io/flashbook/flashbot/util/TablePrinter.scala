package io.flashbook.flashbot.util

/**
  * Thanks! https://gist.github.com/kpmeen/b337308b1682ce0265beaf87f3ea8a37
  */
import fansi.Str
import org.slf4j.LoggerFactory

import scala.util.Try
import scala.util.control.NonFatal

object TablePrinter {

  val logger = LoggerFactory.getLogger(TablePrinter.getClass)

  private val CellPadding = 2

  def format(t: Seq[Seq[Any]]) =
    Try {
      t match {
        case Nil => ""
        case _ =>
          val cellSizes =
            t.map {
              _.map { c =>
                Option(c).fold(0) {
                  case s: String => s.length
                  case fs: Str   => fs.getChars.length
                  case a         => a.toString.length
                } + CellPadding
              }
            }
          val colSizes = cellSizes.transpose.map(_.max)
          val rows     = t.map(r => formatRow(r, colSizes))

          formatRows(rowSeparator(colSizes), rows)
      }
    }.recover {
      case NonFatal(ex) =>
        logger.error("There was an error formatting to a table string", ex)
        ""
    }.getOrElse("")

  def formatRow(row: Seq[Any], colSizes: Seq[Int]) = {
    val cells = row.zip(colSizes).map {
      case (_, size: Int) if size == 0 => ""
      case (item: fansi.Str, size) =>
        val sze          = size - CellPadding
        val diff         = sze - item.getChars.length
        val rightPadding = (0 until diff).map(_ => " ").mkString("")
        (" %-" + sze + "s " + rightPadding).format(item)

      case (item, size) =>
        (" %-" + (size - CellPadding) + "s ").format(item)

    }
    cells.mkString("|", "|", "|")
  }

  def formatRows(separator: String, rows: Seq[String]): String =
    (separator :: rows.head :: separator :: rows.tail.toList ::: separator :: Nil)
      .mkString("\n")

  private def rowSeparator(colSizes: Seq[Int]) =
    colSizes.map(s => "-" * s).mkString("+", "+", "+")
}
