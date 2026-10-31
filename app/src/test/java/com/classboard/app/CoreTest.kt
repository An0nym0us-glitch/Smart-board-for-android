package com.classboard.app

import com.classboard.app.export.Exporters
import com.classboard.app.math.Calculator
import com.classboard.app.math.Expression
import com.classboard.app.math.formatG
import com.classboard.app.storage.Container
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressionTest {
    private fun eval(s: String, vararg vars: Pair<String, Double>, degrees: Boolean = false): Double {
        val (e, err) = Expression.compileWithError(s, degrees)
        assertTrue("compile '$s': $err", e.isValid)
        return e.evaluate(vars.toMap())
    }

    @Test fun arithmetic() {
        assertEquals(7.0, eval("1 + 2 * 3"), 1e-12)
        assertEquals(9.0, eval("(1 + 2) * 3"), 1e-12)
        assertEquals(-8.0, eval("−2³"), 1e-12)
        assertEquals(512.0, eval("2^3^2"), 1e-12)
        assertEquals(120.0, eval("5!"), 1e-12)
        assertEquals(12.0, eval("15% * 80"), 1e-12)
    }

    @Test fun implicitMultiplicationAndParameters() {
        assertEquals(7.0, eval("2x + 1", "x" to 3.0), 1e-12)
        assertEquals(6.0, eval("abx", "a" to 1.0, "b" to 2.0, "x" to 3.0), 1e-12)
        assertEquals(8.0, eval("(x+1)(x-1)", "x" to 3.0), 1e-12)
        assertEquals(5.0, eval("|x|", "x" to -5.0), 1e-12)
        val e = Expression.compile("a*sin(b x) + c")
        assertEquals(listOf("a", "b", "x", "c"), e.variables)
    }

    @Test fun degreesMode() {
        assertEquals(0.5, eval("sin 30", degrees = true), 1e-12)
        assertEquals(30.0, eval("asin(0.5)", degrees = true), 1e-9)
        assertTrue(eval("tan 90", degrees = true).isNaN())
    }

    @Test fun errors() {
        assertFalse(Expression.compile("2 +").isValid)
        assertFalse(Expression.compile("(1 + 2").isValid)
        assertFalse(Expression.compile("").isValid)
        assertEquals("x^2", Expression.stripDefinition("f(x) = x^2").second)
    }
}

class CalculatorTest {
    @Test fun calculate() {
        val c = Calculator()
        val r = c.calculate("12×7")
        assertTrue(r.ok)
        assertEquals("84", r.text)
        assertEquals("21", c.calculate("Ans ÷ 4").text)
        assertEquals("0.5", c.evaluate("sin(30").text)
        assertFalse(c.evaluate("1 ÷ 0").ok)
        assertFalse(c.evaluate("2××3").ok)
    }

    @Test fun formatting() {
        assertEquals("0.333333333333", Calculator.format(1.0 / 3))
        assertEquals("1.5 × 10¹⁵", Calculator.format(1.5e15))
        assertEquals("−2.5", Calculator.format(-2.5))
        assertEquals("1.5e+15", formatG(1.5e15, 12))
        assertEquals("0.0001", formatG(0.0001, 12))
        assertEquals("1e-05", formatG(0.00001, 12))
    }

    @Test fun latex() {
        assertEquals("12 \\times 7 = 84", Calculator.toLatex("12×7", "84"))
        assertEquals("\\sqrt{2} = 1.41421356237", Calculator.toLatex("√2", "1.41421356237"))
    }
}

class ContainerTest {
    @Test fun roundTrip() {
        val c = Container()
        val doc = "{\"format\":\"classboard\"}".toByteArray()
        val img = ByteArray(1000) { (it * 7).toByte() }
        c.add("document.json", doc, true)
        c.add("images/abc.png", img, false)
        val bytes = c.write()
        assertTrue(Container.looksLikeContainer(bytes))
        val r = Container()
        r.read(bytes)
        assertEquals(2, r.entries.size)
        assertArrayEquals(doc, r.find("document.json")!!.data)
        assertArrayEquals(img, r.find("images/abc.png")!!.data)
    }

    @Test fun qCompressFormat() {
        val data = ByteArray(5000) { (it % 13).toByte() }
        val z = Container.qCompress(data)
        // Qt prefix: big-endian uncompressed length.
        assertEquals(0, z[0].toInt()); assertEquals(0, z[1].toInt())
        assertEquals(5000 shr 8, z[2].toInt() and 0xFF); assertEquals(5000 and 0xFF, z[3].toInt() and 0xFF)
        assertArrayEquals(data, Container.qUncompress(z))
    }

    @Test(expected = Container.FormatException::class)
    fun rejectsCorruption() {
        val c = Container()
        c.add("document.json", "hello".toByteArray(), false)
        val bytes = c.write()
        bytes[bytes.size - 1] = 'X'.code.toByte()
        Container().read(bytes)
    }
}

class ExportRangeTest {
    @Test fun ranges() {
        assertEquals(listOf(0, 1, 2, 4), Exporters.parseRange("1-3, 5", 6))
        assertEquals(listOf(3, 4, 5), Exporters.parseRange("4-", 6))
        assertNull(Exporters.parseRange("0", 6))
        assertNull(Exporters.parseRange("7", 6))
        assertNull(Exporters.parseRange("abc", 6))
    }
}
