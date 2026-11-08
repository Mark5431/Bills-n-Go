package com.marklim.billsngo.core.model

data class BillItem(
    val quantity: Int,
    val name: String,
    val amount: Double
) {
    val formattedAmount: String
        get() = String.format("%.2f", amount)
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
    val otherLines: List<String> = emptyList()
) {
    val calculatedTotal: Double
        get() = items.sumOf { it.amount }

    val explicitTotal: Double?
        get() = summaries.firstOrNull {
            it.label.contains("total", ignoreCase = true) &&
                    !it.label.contains("sub", ignoreCase = true)
        }?.amount
}
