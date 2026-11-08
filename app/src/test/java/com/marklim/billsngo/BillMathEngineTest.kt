package com.marklim.billsngo

import com.marklim.billsngo.core.domain.BillMathEngine
import com.marklim.billsngo.core.model.BillItem
import com.marklim.billsngo.core.model.BillModifier
import com.marklim.billsngo.core.model.ModifierType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillMathEngineTest {

    @Test
    fun testSubtotalCalculation() {
        val items = listOf(
            BillItem(1, "Nasi Lemak", 12.50),
            BillItem(2, "Teh Tarik", 7.00)
        )
        val result = BillMathEngine.calculate(items, emptyList())

        assertEquals(19.50, result.subtotal, 0.001)
        assertEquals(19.50, result.grandTotal, 0.001)
        assertTrue(result.modifierBreakdowns.isEmpty())
    }

    @Test
    fun testSst6Calculation() {
        val items = listOf(
            BillItem(1, "Burger Set", 50.00)
        )
        val modifiers = listOf(BillMathEngine.sst6(isCompounded = false))
        val result = BillMathEngine.calculate(items, modifiers)

        assertEquals(50.00, result.subtotal, 0.001)
        assertEquals(3.00, result.modifierBreakdowns[0].amount, 0.001)
        assertEquals(53.00, result.grandTotal, 0.001)
    }

    @Test
    fun testServiceChargeAndCompoundedSst() {
        val items = listOf(
            BillItem(1, "Steak Dinner", 100.00)
        )
        // 10% Service Charge + 6% SST compounded on (Subtotal + Service Charge)
        val modifiers = listOf(
            BillMathEngine.serviceCharge10(),
            BillMathEngine.sst6(isCompounded = true)
        )
        val result = BillMathEngine.calculate(items, modifiers)

        assertEquals(100.00, result.subtotal, 0.001)

        val svcBreakdown = result.modifierBreakdowns.first { it.modifier.type == ModifierType.SERVICE_CHARGE }
        assertEquals(10.00, svcBreakdown.amount, 0.001)

        // 6% of (100 + 10) = 6.60
        val taxBreakdown = result.modifierBreakdowns.first { it.modifier.type == ModifierType.TAX }
        assertEquals(6.60, taxBreakdown.amount, 0.001)

        // Grand Total = 100 + 10 + 6.60 = 116.60
        assertEquals(116.60, result.grandTotal, 0.001)
    }

    @Test
    fun testMalaysian5SenRounding() {
        // Raw total: 10.00 + 6% SST = 10.60 (exact, rounding 0)
        val items1 = listOf(BillItem(1, "Item", 10.00))
        val result1 = BillMathEngine.calculate(items1, listOf(BillMathEngine.sst6(), BillMathEngine.rounding5Sen()))
        assertEquals(10.60, result1.grandTotal, 0.001)

        // Raw total: 10.02 -> rounded to 10.00 (-0.02)
        val items2 = listOf(BillItem(1, "Item", 10.02))
        val result2 = BillMathEngine.calculate(items2, listOf(BillMathEngine.rounding5Sen()))
        assertEquals(10.00, result2.grandTotal, 0.001)
        assertEquals(-0.02, result2.roundingAdjustment, 0.001)

        // Raw total: 10.03 -> rounded to 10.05 (+0.02)
        val items3 = listOf(BillItem(1, "Item", 10.03))
        val result3 = BillMathEngine.calculate(items3, listOf(BillMathEngine.rounding5Sen()))
        assertEquals(10.05, result3.grandTotal, 0.001)
        assertEquals(0.02, result3.roundingAdjustment, 0.001)

        // Raw total: 10.08 -> rounded to 10.10 (+0.02)
        val items4 = listOf(BillItem(1, "Item", 10.08))
        val result4 = BillMathEngine.calculate(items4, listOf(BillMathEngine.rounding5Sen()))
        assertEquals(10.10, result4.grandTotal, 0.001)
        assertEquals(0.02, result4.roundingAdjustment, 0.001)
    }

    @Test
    fun testFixedAndPercentDiscount() {
        val items = listOf(BillItem(1, "Shopping", 100.00))
        val discount = BillModifier("disc", "Member Promo", ModifierType.DISCOUNT_PERCENT, rate = 0.10)
        val sst = BillMathEngine.sst6(isCompounded = false)

        val result = BillMathEngine.calculate(items, listOf(discount, sst))

        assertEquals(100.00, result.subtotal, 0.001)
        // 10% of 100 = -10.00
        val discBreakdown = result.modifierBreakdowns.first { it.modifier.type == ModifierType.DISCOUNT_PERCENT }
        assertEquals(-10.00, discBreakdown.amount, 0.001)

        // 6% on 90 = 5.40
        val sstBreakdown = result.modifierBreakdowns.first { it.modifier.type == ModifierType.TAX }
        assertEquals(5.40, sstBreakdown.amount, 0.001)

        // Total = 90 + 5.40 = 95.40
        assertEquals(95.40, result.grandTotal, 0.001)
    }

    @Test
    fun testReconciliation() {
        val items = listOf(BillItem(1, "Coffee", 15.00))
        val sst = BillMathEngine.sst6(isCompounded = false) // 15.00 * 0.06 = 0.90 -> Total 15.90

        // Explicit total matches
        val matchResult = BillMathEngine.calculate(items, listOf(sst), ocrExplicitTotal = 15.90)
        assertTrue(matchResult.isReconciled)

        // Explicit total does NOT match (e.g. receipt total says 20.00)
        val mismatchResult = BillMathEngine.calculate(items, listOf(sst), ocrExplicitTotal = 20.00)
        assertFalse(mismatchResult.isReconciled)
    }

    @Test
    fun testItemLevelDiscounts() {
        // Item 1: RM 20 with 20% discount -> saves RM 4.00, net RM 16.00
        // Item 2: RM 10 with RM 3 fixed voucher -> saves RM 3.00, net RM 7.00
        val items = listOf(
            BillItem(1, "Pizza", 20.00, discountPercent = 0.20),
            BillItem(1, "Garlic Bread", 10.00, discountFixed = 3.00)
        )
        val result = BillMathEngine.calculate(items, emptyList())

        assertEquals(30.00, result.rawSubtotal, 0.001)
        assertEquals(7.00, result.itemDiscountsTotal, 0.001)
        assertEquals(23.00, result.subtotal, 0.001)
        assertEquals(23.00, result.grandTotal, 0.001)
    }

    @Test
    fun testTargetFlatTotalDiscount() {
        // Bill total with tax: RM 50.00 + 10% Service (RM 5) + 6% SST (RM 3.30) = RM 58.30
        // Customer negotiates/voucher pushes down to flat RM 50.00
        val items = listOf(BillItem(1, "Buffet", 50.00))
        val modifiers = listOf(
            BillMathEngine.serviceCharge10(),
            BillMathEngine.sst6(isCompounded = true),
            BillMathEngine.discountTarget(50.00)
        )
        val result = BillMathEngine.calculate(items, modifiers)

        assertEquals(50.00, result.grandTotal, 0.001)
        val targetBreakdown = result.modifierBreakdowns.first { it.modifier.type == ModifierType.DISCOUNT_TARGET }
        assertEquals(-8.30, targetBreakdown.amount, 0.001)
    }
}
