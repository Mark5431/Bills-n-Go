package com.marklim.billsngo.feature.bill_entry.domain

import com.marklim.billsngo.core.domain.BillMathEngine
import com.marklim.billsngo.core.model.BillItem
import com.marklim.billsngo.core.model.BillModifier
import com.marklim.billsngo.core.model.BillSummary
import com.marklim.billsngo.core.model.ModifierType
import com.marklim.billsngo.core.model.ParsedBill
import java.util.Locale
import kotlin.math.abs

object BillParser {

    private val SUMMARY_KEYWORDS = listOf(
        "subtotal", "sub-total", "sub total", "grand total", "net total",
        "total amount", "amount due", "total", "sst", "gst", "service tax",
        "service charge", "svc chg", "svc tax", "tax", "discount", "disc", "voucher",
        "rebate", "rounding", "round adj", "cash", "change", "balance", "visa",
        "master", "mastercard", "credit card", "debit card", "ewallet", "tng",
        "touch 'n go", "grabpay", "duitnow", "paid"
    )

    private val HEADER_KEYWORDS = listOf(
        "qty", "quantity", "desc", "description", "item", "amt", "amount", "price", "unit price"
    )

    // Patterns indicating metadata/noise rows (Date, Time, Table, Phone, Bill No, Cashier)
    private val METADATA_PATTERNS = listOf(
        Regex("""(?i)\b(?:table|tbl)\s*[:#]?\s*\w+"""),
        Regex("""(?i)\b(?:date|time|dt)\s*[:#]?"""),
        Regex("""\b\d{1,4}[/\-.]\d{1,2}[/\-.]\d{1,4}\b"""),
        Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:am|pm)?\b""", RegexOption.IGNORE_CASE),
        Regex("""(?i)\b(?:tel|phone|fax|pax)\s*[:#]?\s*[0-9+\-]+"""),
        Regex("""(?i)\b(?:receipt|bill|invoice|inv|order|tax inv|gst no|sst no)\s*[:#]?"""),
        Regex("""(?i)\b(?:cashier|waiter|server|counter)\s*[:#]?""")
    )

    private val LEADING_QTY_REGEX = Regex("""^\s*(\d{1,3})\s*(?:[xX*@]\s*|\s+)(.*)$""")
    private val TRAILING_QTY_REGEX = Regex("""^(.*?)\s+[xX*@]\s*(\d{1,3})$""")

    fun parse(rawRowsString: String): ParsedBill {
        val rawLines = rawRowsString.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val items = mutableListOf<BillItem>()
        val summaries = mutableListOf<BillSummary>()
        val modifiers = mutableListOf<BillModifier>()
        val otherLines = mutableListOf<String>()

        var pendingItemPrefix: String? = null

        for (line in rawLines) {
            val normalizedLine = normalizeReceiptLine(line)
            val lower = normalizedLine.lowercase(Locale.ROOT)

            // 1. Skip table header rows (e.g. "QTY   DESC   PRICE   AMT")
            if (isHeaderRow(lower)) {
                otherLines.add(line)
                pendingItemPrefix = null
                continue
            }

            // 2. Check if this is a metadata row (Date, Table, Tel, etc.)
            if (isMetadataRow(normalizedLine)) {
                otherLines.add(line)
                pendingItemPrefix = null
                continue
            }

            // 3. Extract amount and left-side description
            val extraction = extractAmountAndDescription(normalizedLine)
            if (extraction == null) {
                // Line does NOT have a price: candidate for wrapped multi-line item description
                if (isPotentialWrappedItemLine(normalizedLine, lower)) {
                    pendingItemPrefix = if (pendingItemPrefix != null) {
                        "$pendingItemPrefix $normalizedLine"
                    } else {
                        normalizedLine
                    }
                } else {
                    otherLines.add(line)
                    pendingItemPrefix = null
                }
                continue
            }

            val (rawDescription, amount) = extraction
            val lowerDesc = rawDescription.lowercase(Locale.ROOT)

            // 4. Check if this is a Summary row (Subtotal, Total, SST, Discount, etc.)
            val isSummary = SUMMARY_KEYWORDS.any { lowerDesc.contains(it) }
            if (isSummary) {
                summaries.add(BillSummary(label = rawDescription, amount = amount))

                val detectedMod = detectModifierFromSummary(rawDescription, amount)
                if (detectedMod != null) {
                    modifiers.add(detectedMod)
                }

                pendingItemPrefix = null
                continue
            }

            // 5. This is an Item row
            var fullName = if (pendingItemPrefix != null) {
                "$pendingItemPrefix $rawDescription"
            } else {
                rawDescription
            }
            pendingItemPrefix = null

            fullName = stripTrailingUnitPrice(fullName, amount)

            val (quantity, cleanedName) = extractQuantity(fullName)
            val finalName = cleanItemName(cleanedName)

            if (finalName.isNotBlank() && abs(amount) > 0.001) {
                items.add(BillItem(quantity = quantity, name = finalName, amount = amount))
            } else {
                otherLines.add(line)
            }
        }

        return ParsedBill(
            items = items,
            summaries = summaries,
            detectedModifiers = modifiers,
            otherLines = otherLines
        )
    }

    private fun detectModifierFromSummary(label: String, amount: Double): BillModifier? {
        val lower = label.lowercase(Locale.ROOT)
        val percentMatch = Regex("""(\d{1,2}(?:\.\d+)?)\s*%""").find(label)
        val parsedRate = percentMatch?.groupValues?.get(1)?.toDoubleOrNull()?.div(100.0)

        return when {
            lower.contains("service charge") || lower.contains("svc chg") || lower.contains("svc charge") -> {
                BillModifier(
                    name = if (parsedRate != null) "Service Charge (${(parsedRate * 100).toInt()}%)" else "Service Charge (10%)",
                    type = ModifierType.SERVICE_CHARGE,
                    rate = parsedRate ?: 0.10,
                    fixedAmount = if (parsedRate == null) abs(amount) else null
                )
            }
            lower.contains("sst") || lower.contains("service tax") || lower.contains("svc tax") || lower.contains("tax") || lower.contains("gst") -> {
                BillModifier(
                    name = if (parsedRate != null) "SST (${(parsedRate * 100).toInt()}%)" else "SST (6%)",
                    type = ModifierType.TAX,
                    rate = parsedRate ?: 0.06,
                    fixedAmount = if (parsedRate == null) abs(amount) else null,
                    isCompounded = true
                )
            }
            lower.contains("rounding") || lower.contains("round adj") -> {
                BillMathEngine.rounding5Sen()
            }
            lower.contains("discount") || lower.contains("disc") || lower.contains("voucher") || lower.contains("rebate") -> {
                if (parsedRate != null) {
                    BillModifier(name = label, type = ModifierType.DISCOUNT_PERCENT, rate = parsedRate)
                } else {
                    BillModifier(name = label, type = ModifierType.DISCOUNT_FIXED, fixedAmount = abs(amount))
                }
            }
            else -> null
        }
    }

    fun normalizeReceiptLine(line: String): String {
        var text = line

        text = text.replace(Regex("""(\d+)\s*[,.]\s*([0-9OolIS]{2})\b""")) { match ->
            val whole = match.groupValues[1]
            val decimal = cleanDecimalDigits(match.groupValues[2])
            "$whole.$decimal"
        }

        text = text.replace(Regex("""(?i)(RM|MYR|\$)\s*(\d+)\s*[:\s]\s*([0-9OolIS]{2})\b""")) { match ->
            val curr = match.groupValues[1].uppercase(Locale.ROOT)
            val whole = match.groupValues[2]
            val decimal = cleanDecimalDigits(match.groupValues[3])
            "$curr $whole.$decimal"
        }

        text = text.replace(Regex("""(?i)(?:RM|MYR|\$)?\s*(\d{1,4})\s+([0-9OolIS]{2})\s*(?:[A-Za-z*#]{1,3})?\s*$""")) { match ->
            val whole = match.groupValues[1]
            val decimal = cleanDecimalDigits(match.groupValues[2])
            match.value.replaceFirst("$whole $decimal", "$whole.$decimal")
        }

        return text.trim()
    }

    private fun cleanDecimalDigits(raw: String): String {
        return raw.map { ch ->
            when (ch) {
                'O', 'o' -> '0'
                'l', 'I', 'i' -> '1'
                'S', 's' -> '5'
                'B' -> '8'
                else -> ch
            }
        }.joinToString("")
    }

    private fun extractAmountAndDescription(line: String): Pair<String, Double>? {
        val regex = Regex(
            """(?i)([-(]?)\s*(?:RM|MYR|\$)?\s*([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2})\s*([)]?)\s*(?:[A-Za-z*#]{1,3})?\s*$"""
        )

        val match = regex.find(line) ?: return null

        val hasLeadingNeg = match.groupValues[1] == "-" || match.groupValues[1] == "("
        val hasTrailingParen = match.groupValues[3] == ")"
        val isNegative = hasLeadingNeg || hasTrailingParen

        val rawDigits = match.groupValues[2].replace(",", "")
        var amount = rawDigits.toDoubleOrNull() ?: return null

        if (isNegative) {
            amount = -amount
        }

        val textBefore = line.substring(0, match.range.first).trim()
        if (textBefore.isBlank()) return null

        return Pair(textBefore, amount)
    }

    private fun stripTrailingUnitPrice(name: String, totalAmount: Double): String {
        val unitPriceMatch = Regex("""\b(?:RM|MYR|\$)?\s*([0-9]{1,4}(?:[.,][0-9]{3})*[.,][0-9]{2})\s*$""").find(name)
            ?: return name

        val trimmedName = name.substring(0, unitPriceMatch.range.first).trim()
        return if (trimmedName.isBlank()) name else trimmedName
    }

    private fun extractQuantity(rawName: String): Pair<Int, String> {
        if (rawName.startsWith("#") || rawName.startsWith("[")) {
            return Pair(1, rawName)
        }

        val leadingMatch = LEADING_QTY_REGEX.find(rawName)
        if (leadingMatch != null) {
            val qtyStr = leadingMatch.groupValues[1]
            val remName = leadingMatch.groupValues[2].trim()

            if (!qtyStr.startsWith("0") && remName.isNotBlank()) {
                val qty = qtyStr.toIntOrNull()
                if (qty != null && qty in 1..999) {
                    return Pair(qty, remName)
                }
            }
        }

        val trailingMatch = TRAILING_QTY_REGEX.find(rawName)
        if (trailingMatch != null) {
            val remName = trailingMatch.groupValues[1].trim()
            val qtyStr = trailingMatch.groupValues[2]
            val qty = qtyStr.toIntOrNull()
            if (qty != null && qty in 1..999 && remName.isNotBlank()) {
                return Pair(qty, remName)
            }
        }

        return Pair(1, rawName)
    }

    private fun isHeaderRow(lowerLine: String): Boolean {
        val words = lowerLine.split(Regex("""\s+"""))
        val matchCount = words.count { word ->
            HEADER_KEYWORDS.any { header -> word == header || word.startsWith(header) }
        }
        return matchCount >= 2
    }

    private fun isMetadataRow(line: String): Boolean {
        return METADATA_PATTERNS.any { it.containsMatchIn(line) }
    }

    private fun isPotentialWrappedItemLine(line: String, lower: String): Boolean {
        if (line.length < 2) return false
        if (SUMMARY_KEYWORDS.any { lower.contains(it) }) return false
        if (isMetadataRow(line)) return false
        if (isHeaderRow(lower)) return false
        return line.any { it.isLetter() }
    }

    private fun cleanItemName(name: String): String {
        return name
            .replace(Regex("""^[\s\-•*#:@]+"""), "")
            .replace(Regex("""[\s\-•*#:@]+$"""), "")
            .trim()
    }
}
