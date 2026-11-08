package com.marklim.billsngo

import com.marklim.billsngo.feature.bill_entry.domain.BillParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillParserTest {

    @Test
    fun testSpacedAndCommaDecimals() {
        val input = """
            NASI LEMAK   9, 00
            TEH O ICE   RM 3 . 50
            KOPI AIS   MYR 4 , 00
            ROTI BAKAR   RM 2 50
        """.trimIndent()

        val bill = BillParser.parse(input)
        assertEquals(4, bill.items.size)
        assertEquals(9.00, bill.items[0].amount, 0.001)
        assertEquals(3.50, bill.items[1].amount, 0.001)
        assertEquals(4.00, bill.items[2].amount, 0.001)
        assertEquals(2.50, bill.items[3].amount, 0.001)
    }

    @Test
    fun testOcrLetterSwapsInDecimalPart() {
        val input = """
            CHICKEN RICE   12.5O
            MILO AIS   4.SO
        """.trimIndent()

        val bill = BillParser.parse(input)
        assertEquals(2, bill.items.size)
        assertEquals(12.50, bill.items[0].amount, 0.001)
        assertEquals(4.50, bill.items[1].amount, 0.001)
    }

    @Test
    fun testTrailingTaxAndCategoryCodes() {
        val input = """
            NASI LEMAK AYAM   14.50 S
            TEH TARIK   3.20 SR*
            AMERICANO   RM 8.00 T
            MATCHA LATTE   15.50 *
        """.trimIndent()

        val bill = BillParser.parse(input)
        assertEquals(4, bill.items.size)
        assertEquals("NASI LEMAK AYAM", bill.items[0].name)
        assertEquals(14.50, bill.items[0].amount, 0.001)
        assertEquals("TEH TARIK", bill.items[1].name)
        assertEquals(3.20, bill.items[1].amount, 0.001)
        assertEquals("AMERICANO", bill.items[2].name)
        assertEquals(8.00, bill.items[2].amount, 0.001)
        assertEquals("MATCHA LATTE", bill.items[3].name)
        assertEquals(15.50, bill.items[3].amount, 0.001)
    }

    @Test
    fun testMultiLineWrappedItemDescriptions() {
        val input = """
            PISTACHIO MCF
            + 6 NUGGETS   16.50
            MATCHA LATTE (COLD)
            OAT MILK SUB   15.00
        """.trimIndent()

        val bill = BillParser.parse(input)
        assertEquals(2, bill.items.size)
        assertEquals("PISTACHIO MCF + 6 NUGGETS", bill.items[0].name)
        assertEquals(16.50, bill.items[0].amount, 0.001)
        assertEquals("MATCHA LATTE (COLD) OAT MILK SUB", bill.items[1].name)
        assertEquals(15.00, bill.items[1].amount, 0.001)
    }

    @Test
    fun testNegativeAmountsAndDiscounts() {
        val input = """
            BURGER SET   22.00
            FRIES   6.00
            SUBTOTAL   28.00
            VOUCHER DISC   -5.00
            PROMO CODE   (3.00)
            TOTAL   20.00
        """.trimIndent()

        val bill = BillParser.parse(input)
        assertEquals(2, bill.items.size)
        assertEquals(4, bill.summaries.size)

        val voucher = bill.summaries.firstOrNull { it.label.contains("VOUCHER", ignoreCase = true) }
        assertNotNull(voucher)
        assertEquals(-5.00, voucher!!.amount, 0.001)

        val promo = bill.summaries.firstOrNull { it.label.contains("PROMO", ignoreCase = true) }
        assertNotNull(promo)
        assertEquals(-3.00, promo!!.amount, 0.001)

        assertEquals(20.00, bill.explicitTotal ?: 0.0, 0.001)
    }

    @Test
    fun testMultiColumnUnitPriceDeduplication() {
        val input = """
            QTY   DESC   PRICE   TOTAL
            2   AMERICANO   6.00   12.00
            3   ROTI CANAI   1.50   4.50
        """.trimIndent()

        val bill = BillParser.parse(input)
        assertEquals(2, bill.items.size)
        assertEquals(2, bill.items[0].quantity)
        assertEquals("AMERICANO", bill.items[0].name)
        assertEquals(12.00, bill.items[0].amount, 0.001)

        assertEquals(3, bill.items[1].quantity)
        assertEquals("ROTI CANAI", bill.items[1].name)
        assertEquals(4.50, bill.items[1].amount, 0.001)
    }

    @Test
    fun testMetadataNoiseExclusion() {
        val input = """
            TABLE 14
            DATE: 12/10/2024   18:42
            TEL: 03-77221234
            INV NO: #004521
            1   CHICKEN CHOP   18.50
            TOTAL   18.50
        """.trimIndent()

        val bill = BillParser.parse(input)
        assertEquals(1, bill.items.size)
        assertEquals("CHICKEN CHOP", bill.items[0].name)
        assertEquals(18.50, bill.items[0].amount, 0.001)
        assertEquals(18.50, bill.explicitTotal ?: 0.0, 0.001)
        assertTrue(bill.otherLines.any { it.contains("TABLE 14") })
    }
}
