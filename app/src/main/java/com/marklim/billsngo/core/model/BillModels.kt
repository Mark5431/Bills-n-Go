package com.marklim.billsngo.core.model

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min

data class BillItem(
    val quantity: Int,
    val name: String,
    val amount: Double,
    val discountPercent: Double? = null, // e.g. 0.20 for 20%
    val discountFixed: Double? = null     // e.g. 3.00 for RM 3.00 off
) {
    val discountAmount: Double
        get() = when {
            discountPercent != null -> {
                BigDecimal.valueOf(amount * discountPercent)
                    .setScale(2, RoundingMode.HALF_UP)
                    .toDouble()
            }
            discountFixed != null -> min(amount, discountFixed)
            else -> 0.0
        }

    val effectiveAmount: Double
        get() = max(0.0, BigDecimal.valueOf(amount - discountAmount)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble())

    val formattedAmount: String
        get() = String.format("%.2f", amount)

    val formattedEffectiveAmount: String
        get() = String.format("%.2f", effectiveAmount)

    val discountLabel: String?
        get() = when {
            discountPercent != null -> String.format("-%.0f%%", discountPercent * 100)
            discountFixed != null -> String.format("-RM %.2f", discountFixed)
            else -> null
        }
}

data class BillSummary(
    val label: String,
    val amount: Double
) {
    val formattedAmount: String
        get() = String.format("%.2f", amount)
}

data class ParsedBill(
    val items: List<BillItem> = emptyList(),
    val summaries: List<BillSummary> = emptyList(),
    val detectedModifiers: List<BillModifier> = emptyList(),
    val otherLines: List<String> = emptyList()
) {
    val calculatedTotal: Double
        get() = items.sumOf { it.effectiveAmount }

    val explicitTotal: Double?
        get() = summaries.firstOrNull {
            it.label.contains("total", ignoreCase = true) &&
                    !it.label.contains("sub", ignoreCase = true)
        }?.amount
}
