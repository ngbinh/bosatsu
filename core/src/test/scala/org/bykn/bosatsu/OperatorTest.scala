package org.bykn.bosatsu

import org.typelevel.paiges.{ Doc, Document }
import Parser.Combinators
import fastparse.all._

class OperatorTest extends ParserTestBase {

  import Operators.Formula
  import TestUtils.runBosatsuTest

  sealed abstract class F {
    def toFormula: Formula[String] =
      this match {
        case F.Num(s) => Formula.Sym(s)
        case F.Form(Formula.Sym(n)) => n.toFormula
        case F.Form(Formula.Op(left, op, right)) =>
          Formula.Op(F.Form(left).toFormula, op, F.Form(right).toFormula)
      }
  }
  object F {
    case class Num(str: String) extends F
    case class Form(toForm: Formula[F]) extends F
  }

  lazy val formP: Parser[F] =
    Operators.Formula
      .parser(Parser.integerString.map(F.Num(_)) |
        P(formP.parens)).map(F.Form(_))

  implicit val document: Document[Formula[String]] =
    Document.instance[Formula[String]] {
      case Formula.Sym(n) => Doc.text(n)
      case Formula.Op(l, o, r) =>
        document.document(l) + Doc.text(o) + document.document(r)
    }

  def parseSame(left: String, right: String) =
    assert(parseUnsafe(formP, left).toFormula == parseUnsafe(formP, right).toFormula)

  test("we can parse integer formulas") {
    parseSame("1+2", "1 + 2")
    parseSame("1+(2*3)", "1 + 2*3")
    parseSame("1+2+3", "(1 + 2) + 3")
    parseSame("1+2+3+4", "((1 + 2) + 3) + 4")
    parseSame("1*2+3*4", "(1 * 2) + (3 * 4)")
    parseSame("1&2|3&4", "(1 & 2) | (3 & 4)")
    parseSame("1&2^3&4", "(1 & 2) ^ (3 & 4)")
    parseSame("1 < 2 & 3 < 4", "(1 < 2) & (3 < 4)")
    parseSame("1 <= 2 & 3 <= 4", "(1 <= 2) & (3 <= 4)")
    parseSame("1 <= 2 < 3", "(1 <= 2) < 3")
    parseSame("0 < 1 <= 2", "0 < (1 <= 2)")
    parseSame("1 ** 2 * 3", "(1 ** 2) * 3")
    parseSame("3 * 1 ** 2", "3 * (1 ** 2)")
    parseSame("1 + 2 == 2 + 1", "(1 + 2) == (2 + 1)")
    parseSame("1 + 2 * 3 == 1 + (2 * 3)", "(1 + (2*3)) == (1 + (2 * 3))")
  }

  test("test operator precedence in real programs") {
    runBosatsuTest(List("""
package Test

operator + = add
operator * = times
operator == = eq_Int

test = Test("precedence", [
   Assertion(1 + 2 * 3 == 1 + (2 * 3), "p1"),
   Assertion(1 + 2 * 3 == 1 + (2 * 3), "p1"),
   Assertion(1 + 2 * 3 == 1 + (2 * 3), "p1")
   ])
"""), "Test", 3)
  }
}