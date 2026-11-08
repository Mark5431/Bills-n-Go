package com.marklim.billsngo.core.model

import java.util.UUID

enum class ModifierType {
    TAX,                // e.g. SST 6%, SST 8%, GST 6%
    SERVICE_CHARGE,     // e.g. 10% F&B service charge
    DISCOUNT_PERCENT,   // e.g. 10% member discount on bill
    DISCOUNT_FIXED,     // e.g. RM 5.00 voucher
    DISCOUNT_TARGET,    // Push down to target flat total (e.g. pay RM 50.00 flat)
    ROUNDING,           // 5-sen rounding mechanism
    CUSTOM
}

data class BillModifier(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ModifierType,
    val rate: Double? = null,           // e.g. 0.06 for 6%, 0.10 for 10%
    val fixedAmount: Double? = null,    // e.g. 5.00 for RM5 discount
    val targetTotal: Double? = null,    // Target flat price (e.g. 50.00)
    val isCompounded: Boolean = false,  // If true, tax applies to (Subtotal + Service Charge)
    val isEnabled: Boolean = true
) {
    val ratePercentageText: String?
        get() = rate?.let { String.format("%.0f%%", it * 100) }
}

data class CalculatedModifierBreakdown(
    val modifier: BillModifier,
    val amount: Double
) {
    val formattedAmount: String
        get() = String.format("%.2f", amount)
}

data class BillCalculationResult(
    val rawSubtotal: Double,
    val itemDiscountsTotal: Double = 0.0,
    val subtotal: Double,
    val modifierBreakdowns: List<CalculatedModifierBreakdown> = emptyList(),
    val roundingAdjustment: Double = 0.0,
    val grandTotal: Double,
    val ocrExplicitTotal: Double? = null
) {
    val formattedRawSubtotal: String
        get() = String.format("%.2f", rawSubtotal)

    val formattedSubtotal: String
        get() = String.format("%.2f", subtotal)

    val formattedGrandTotal: String
        get() = String.format("%.2f", grandTotal)

    val formattedRounding: String
        get() = String.format("%+.2f", roundingAdjustment)

    val isReconciled: Boolean
        get() = ocrExplicitTotal == null || kotlin.math.abs(grandTotal - ocrExplicitTotal) < 0.06

    val discrepancy: Double?
        get() = ocrExplicitTotal?.let { grandTotal - it }
}
