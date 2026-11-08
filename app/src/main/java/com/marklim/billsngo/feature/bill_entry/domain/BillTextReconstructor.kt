package com.marklim.billsngo.feature.bill_entry.domain

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.max
import kotlin.math.min

object BillTextReconstructor {

    private data class LineWrapper(
        val line: Text.Line,
        val box: Rect,
        val top: Int = box.top,
        val bottom: Int = box.bottom,
        val left: Int = box.left,
        val right: Int = box.right,
        val height: Int = max(1, box.height()),
        val centerY: Float = (box.top + box.bottom) / 2.0f
    )

    private class Row(initialLine: LineWrapper) {
        val lines = mutableListOf(initialLine)
        var rowTop: Int = initialLine.top
        var rowBottom: Int = initialLine.bottom

        fun canInclude(candidate: LineWrapper): Boolean {
            // Check 1: Vertical center of candidate is within the current row bounds
            if (candidate.centerY >= rowTop && candidate.centerY <= rowBottom) {
                return true
            }

            // Check 2: Vertical center of row is within candidate bounds
            val rowCenterY = (rowTop + rowBottom) / 2.0f
            if (rowCenterY >= candidate.top && rowCenterY <= candidate.bottom) {
                return true
            }

            // Check 3: Significant vertical overlap
            val overlapTop = max(rowTop, candidate.top)
            val overlapBottom = min(rowBottom, candidate.bottom)
            val overlapHeight = max(0, overlapBottom - overlapTop)
            val minHeight = min(rowBottom - rowTop, candidate.height)

            return overlapHeight >= (minHeight * 0.40f)
        }

        fun add(candidate: LineWrapper) {
            lines.add(candidate)
            rowTop = min(rowTop, candidate.top)
            rowBottom = max(rowBottom, candidate.bottom)
        }

        fun toFormattedString(): String {
            // Sort left-to-right
            val sorted = lines.sortedBy { it.left }
            return sorted.joinToString(separator = "   ") { it.line.text.trim() }
        }
    }

    fun reconstruct(visionText: Text): String {
        // Collect all lines across all text blocks
        val allLines = mutableListOf<LineWrapper>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox
                if (box != null && line.text.isNotBlank()) {
                    allLines.add(LineWrapper(line, box))
                }
            }
        }

        if (allLines.isEmpty()) {
            return ""
        }

        // Sort vertically from top to bottom
        allLines.sortBy { it.top }

        // Cluster lines into horizontal rows
        val rows = mutableListOf<Row>()
        for (line in allLines) {
            var matchedRow: Row? = null
            // Check recent rows to account for slight skews or multi-column entries
            for (i in rows.indices.reversed()) {
                if (rows[i].canInclude(line)) {
                    matchedRow = rows[i]
                    break
                }
            }

            if (matchedRow != null) {
                matchedRow.add(line)
            } else {
                rows.add(Row(line))
            }
        }

        // Sort rows top-to-bottom by their top coordinate
        rows.sortBy { it.rowTop }

        return rows.joinToString(separator = "\n") { it.toFormattedString() }
    }
}
