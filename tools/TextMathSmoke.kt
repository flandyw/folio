package com.folio.notes

import com.folio.notes.math.TextMath
import com.folio.notes.math.TextMath.Part

fun main() {
    fun formulas(text: String) = TextMath.split(text).filterIsInstance<Part.Formula>()
    check(TextMath.split("plain *text* with no math") == listOf(Part.Text("plain *text* with no math"))) { "Plain text was touched" }
    check(!TextMath.hasMath("It costs \$5 and \$6 total")) { "Money read as math" }
    check(!TextMath.hasMath("\$ x \$")) { "Spaced dollars read as math" }
    val inline = TextMath.split("Solve \$x^2=4\$ now")
    check(inline == listOf(Part.Text("Solve "), Part.Formula("\$x^2=4\$", "x^2=4", false), Part.Text(" now"))) { "Inline: $inline" }
    check(formulas("\$\$\\int_0^1 x\\,dx\$\$").single().display) { "Display dollars" }
    check(formulas("a \\[ y=1 \\] b").single().let { it.display && it.latex.trim() == "y=1" }) { "Bracket display" }
    check(formulas("a \\(y=1\\) b").single().let { !it.display && it.latex == "y=1" }) { "Paren inline" }
    check(formulas("\$a\$ and \$b\$").map { it.latex } == listOf("a", "b"))
    check(TextMath.split("only \\\$5 here") == listOf(Part.Text("only \\\$5 here"))) { "No math, so no rewriting" }
    val escaped = TextMath.split("pay \\\$5 for \$x\$")
    check(escaped.first() == Part.Text("pay \$5 for ")) { "Escaped dollar: \$escaped" }
    check(formulas("unclosed \$x^2 forever").isEmpty())
    check(formulas("\$\$\nx\n\$\$").single().latex == "\nx\n") { "Multi-line display" }
    check(formulas("\$   \$").isEmpty() && formulas("\$\$\$\$").isEmpty()) { "Empty formulas" }
    check(formulas("\$a\n\nb\$").isEmpty()) { "A blank line ends an inline formula" }
    println("Text math smoke passed")
}
