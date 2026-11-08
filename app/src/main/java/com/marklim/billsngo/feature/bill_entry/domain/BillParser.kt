package com.marklim.billsngo.feature.bill_entry.domain

import com.marklim.billsngo.core.model.BillItem
import com.marklim.billsngo.core.model.BillSummary
import com.marklim.billsngo.core.model.ParsedBill
import java.util.Locale

object BillParser {

    private val SUMMARY_KEYWORDS = listOf(
        "subtotal", "sub-total", "sub total", "grand total", "net total",
        "total amount", "amount due", "total", "sst", "gst", "service tax",
        "service charge", "svc chg", "tax", "discount", "disc", "rounding",
        "round adj", "cash", "change", "balance", "visa", "master", "mastercard",
        "credit card", "debit card", "ewallet", "tng", "grabpay", "duitnow", "paid"
    )

    private val HEADER_KEYWORDS = listOf(
        "qty", "quantity", "desc", "description", "item", "amt", "amount", "price", "unit price"
    )

    // Matches currency amounts like RM12.50, 12.50, 1,234.50, 12,50
    private val AMOUNT_REGEX = Regex("""(?:RM|rm|\$)?\s*([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2})\s*(?:RM|rm)?$""")

    // Matches leading quantity such as "1 ", "2x ", "3X ", "1* "
    private val LEADING_QTY_REGEX = Regex("""^\s*(\d{1,3})\s*(?:[xX*@]\s*|\s+)(.*)$""")

    fun parse(rawRowsString: String): ParsedBill {
        val lines = rawRowsString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val items = mutableListOf<BillItem>()
        val summaries = mutableListOf<BillSummary>()
        val otherLines = mutableListOf<String>()

        for (line in lines) {
            val lower = line.lowercase(Locale.ROOT)

            // Skip table header rows (e.g. "QTY DESC AMT (RM)")
            if (isHeaderRow(lower)) {
                otherLines.add(line)
                continue
            }

            // Attempt to extract amount from the end of the line
            val amountMatch = AMOUNT_REGEX.find(line)
            if (amountMatch == null) {
                otherLines.add(line)
                continue
            }

            val rawAmountStr = amountMatch.groupValues[1]
                .replace(",", ".") // standardize decimal comma
            val amount = rawAmountStr.toDoubleOrNull()
            if (amount == null) {
                otherLines.add(line)
                continue
            }

            val textBeforeAmount = line.substring(0, amountMatch.range.first).trim()
            if (textBeforeAmount.isBlank()) {
                otherLines.add(line)
                continue
            }

            val lowerText = textBeforeAmount.lowercase(Locale.ROOT)

            // Check if this is a summary row (Total, Subtotal, SST, Rounding, etc.)
            val matchingSummaryKeyword = SUMMARY_KEYWORDS.firstOrNull { lowerText.contains(it) }
            if (matchingSummaryKeyword != null) {
                summaries.add(BillSummary(label = textBeforeAmount, amount = amount))
                continue
            }

            // Otherwise, treat as an Item row
            var quantity = 1
            var itemName = textBeforeAmount

            // Check for leading quantity (e.g., "2 Fried Rice" or "2x Fried Rice")
            val qtyMatch = LEADING_QTY_REGEX.find(textBeforeAmount)
            if (qtyMatch != null) {
                val parsedQty = qtyMatch.groupValues[1].toIntOrNull()
                val parsedName = qtyMatch.groupValues[2].trim()
                if (parsedQty != null && parsedQty in 1..999 && parsedName.isNotBlank()) {
                    quantity = parsedQty
                    itemName = parsedName
                }
            }

            // Clean up item name from unwanted trailing/leading symbols
            itemName = cleanItemName(itemName)

            if (itemName.isNotBlank()) {
                items.add(BillItem(quantity = quantity, name = itemName, amount = amount))
            } else {
                otherLines.add(line)
            }
        }

        return ParsedBill(items = items, summaries = summaries, otherLines = otherLines)
    }

    private fun isHeaderRow(lowerLine: String): Boolean {
        val words = lowerLine.split(Regex("""\s+"""))
        val matchCount = words.count { word ->
            HEADER_KEYWORDS.any { header -> word == header || word.startsWith(header) }
        }
        return matchCount >= 2
    }

    private fun cleanItemName(name: String): String {
        return name.replace(Regex("""^[\s\-•*#:]+"""), "")
            .replace(Regex("""[\s\-•*#:]+$"""), "")
            .trim()
    }
}
