package com.marklim.billsngo.core.domain

import com.marklim.billsngo.core.model.BillCalculationResult
import com.marklim.billsngo.core.model.BillItem
import com.marklim.billsngo.core.model.BillModifier
import com.marklim.billsngo.core.model.CalculatedModifierBreakdown
import com.marklim.billsngo.core.model.ModifierType
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.round

object BillMathEngine {

    /**
     * Standard Malaysian presets
     */
    fun sst6(isCompounded: Boolean = true) = BillModifier(
        id = "sst_6",
        name = "SST (6%)",
        type = ModifierType.TAX,
        rate = 0.06,
        isCompounded = isCompounded
    )

    fun sst8(isCompounded: Boolean = true) = BillModifier(
        id = "sst_8",
        name = "SST (8%)",
        type = ModifierType.TAX,
        rate = 0.08,
        isCompounded = isCompounded
    )

    fun serviceCharge10() = BillModifier(
        id = "svc_10",
        name = "Service Charge (10%)",
        type = ModifierType.SERVICE_CHARGE,
        rate = 0.10
    )

    fun rounding5Sen() = BillModifier(
        id = "round_5",
        name = "Rounding (5 sen)",
        type = ModifierType.ROUNDING
    )

    fun discountPercent(rate: Double, name: String = "Discount (${(rate * 100).toInt()}%)") = BillModifier(
        name = name,
        type = ModifierType.DISCOUNT_PERCENT,
        rate = rate
    )

    fun discountFixed(amount: Double, name: String = "Voucher (-RM ${String.format("%.2f", amount)})") = BillModifier(
        name = name,
        type = ModifierType.DISCOUNT_FIXED,
        fixedAmount = amount
    )

    fun discountTarget(targetTotal: Double, name: String = "Target Total (RM ${String.format("%.2f", targetTotal)})") = BillModifier(
        name = name,
        type = ModifierType.DISCOUNT_TARGET,
        targetTotal = targetTotal
    )

    /**
     * Calculates reactive totals from line items, item-level discounts,
     * order-level discounts (percentage, fixed, or target price), service charges,
     * compounding taxes, and cash rounding.
     */
    fun calculate(
        items: List<BillItem>,
        modifiers: List<BillModifier>,
        ocrExplicitTotal: Double? = null
    ): BillCalculationResult {
        // 1. Raw Subtotal & Item-Level Discounts
        val rawSubtotal = roundCurrency(items.sumOf { it.amount })
        val itemDiscountsTotal = roundCurrency(items.sumOf { it.discountAmount })
        val subtotal = roundCurrency(items.sumOf { it.effectiveAmount })

        val activeModifiers = modifiers.filter { it.isEnabled }
        val breakdowns = mutableListOf<CalculatedModifierBreakdown>()

        // 2. Order-Level Discounts (Percentage & Fixed Amount)
        var orderDiscountsTotal = 0.0
        val discountMods = activeModifiers.filter {
            it.type == ModifierType.DISCOUNT_PERCENT || it.type == ModifierType.DISCOUNT_FIXED
        }
        for (mod in discountMods) {
            val discAmount = when (mod.type) {
                ModifierType.DISCOUNT_PERCENT -> roundCurrency(subtotal * (mod.rate ?: 0.0))
                ModifierType.DISCOUNT_FIXED -> roundCurrency(mod.fixedAmount ?: 0.0)
                else -> 0.0
            }
            if (discAmount > 0.0) {
                orderDiscountsTotal += discAmount
                breakdowns.add(CalculatedModifierBreakdown(mod, -discAmount))
            }
        }

        val taxableSubtotal = max(0.0, roundCurrency(subtotal - orderDiscountsTotal))

        // 3. Service Charges (e.g. 10%)
        var totalServiceCharge = 0.0
        val serviceMods = activeModifiers.filter { it.type == ModifierType.SERVICE_CHARGE }
        for (mod in serviceMods) {
            val svcAmt = when {
                mod.rate != null -> roundCurrency(taxableSubtotal * mod.rate)
                mod.fixedAmount != null -> roundCurrency(mod.fixedAmount)
                else -> 0.0
            }
            if (svcAmt > 0.0) {
                totalServiceCharge += svcAmt
                breakdowns.add(CalculatedModifierBreakdown(mod, svcAmt))
            }
        }

        // 4. Taxes (SST 6%/8%, GST)
        // If isCompounded is true, tax applies to (Taxable Subtotal + Service Charge) per Malaysian law
        var totalTax = 0.0
        val taxMods = activeModifiers.filter { it.type == ModifierType.TAX || it.type == ModifierType.CUSTOM }
        for (mod in taxMods) {
            val taxBase = if (mod.isCompounded) {
                taxableSubtotal + totalServiceCharge
            } else {
                taxableSubtotal
            }
            val taxAmt = when {
                mod.rate != null -> roundCurrency(taxBase * mod.rate)
                mod.fixedAmount != null -> roundCurrency(mod.fixedAmount)
                else -> 0.0
            }
            if (taxAmt > 0.0) {
                totalTax += taxAmt
                breakdowns.add(CalculatedModifierBreakdown(mod, taxAmt))
            }
        }

        // 5. Pre-Target / Pre-Rounding Total
        var intermediateTotal = roundCurrency(taxableSubtotal + totalServiceCharge + totalTax)

        // 6. Target Price Discount ("Push down to literal number", e.g. pay RM 50 flat)
        val targetMod = activeModifiers.firstOrNull { it.type == ModifierType.DISCOUNT_TARGET && it.targetTotal != null }
        if (targetMod != null && targetMod.targetTotal != null) {
            val target = targetMod.targetTotal
            if (intermediateTotal > target) {
                val targetDiscount = roundCurrency(intermediateTotal - target)
                breakdowns.add(CalculatedModifierBreakdown(targetMod, -targetDiscount))
                intermediateTotal = target
            }
        }

        // 7. 5-Sen Rounding (Bank Negara Malaysia rule)
        val roundingMod = activeModifiers.firstOrNull { it.type == ModifierType.ROUNDING }
        var roundingAdjustment = 0.0
        val grandTotal: Double

        if (roundingMod != null && intermediateTotal > 0.0 && targetMod == null) {
            val rounded = round(intermediateTotal * 20.0) / 20.0
            val roundedCurrency = roundCurrency(rounded)
            roundingAdjustment = roundCurrency(roundedCurrency - intermediateTotal)
            grandTotal = roundedCurrency

            if (kotlin.math.abs(roundingAdjustment) > 0.001) {
                breakdowns.add(CalculatedModifierBreakdown(roundingMod, roundingAdjustment))
            }
        } else {
            grandTotal = intermediateTotal
        }

        return BillCalculationResult(
            rawSubtotal = rawSubtotal,
            itemDiscountsTotal = itemDiscountsTotal,
            subtotal = subtotal,
            modifierBreakdowns = breakdowns,
            roundingAdjustment = roundingAdjustment,
            grandTotal = grandTotal,
            ocrExplicitTotal = ocrExplicitTotal
        )
    }

    private fun roundCurrency(amount: Double): Double {
        return BigDecimal.valueOf(amount)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }
}
