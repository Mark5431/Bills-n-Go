package com.marklim.billsngo.feature.bill_entry.ui

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.marklim.billsngo.R
import com.marklim.billsngo.core.domain.BillMathEngine
import com.marklim.billsngo.core.model.BillItem
import com.marklim.billsngo.core.model.BillModifier
import com.marklim.billsngo.core.model.ModifierType
import com.marklim.billsngo.databinding.SheetDiscountPickerBinding
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

object DiscountBottomSheetHelper {

    enum class DiscountMode {
        PERCENT,
        FIXED_RM,
        TARGET_TOTAL
    }

    /**
     * Show discount picker for a single line item.
     */
    fun showForItem(
        context: Context,
        item: BillItem,
        onApply: (BillItem) -> Unit,
        onRemove: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = SheetDiscountPickerBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.tvSheetTitle.text = "Discount: ${item.name.ifEmpty { "Item" }}"
        binding.tvSheetSubtitle.text = "Apply percentage or fixed amount off for this item"

        // Target total is not applicable to a single line item
        binding.btnTypeTarget.visibility = View.GONE

        var activeMode = when {
            item.discountPercent != null -> DiscountMode.PERCENT
            item.discountFixed != null -> DiscountMode.FIXED_RM
            else -> DiscountMode.PERCENT
        }

        val baseAmount = item.amount
        binding.tvPreviewOriginalLabel.text = "Item Base Price"
        binding.tvPreviewOriginalAmount.text = String.format("RM %.2f", baseAmount)

        // Show remove button if item has existing discount
        val hasExistingDiscount = item.discountPercent != null || item.discountFixed != null
        if (hasExistingDiscount) {
            binding.btnRemoveDiscount.visibility = View.VISIBLE
            binding.btnRemoveDiscount.setOnClickListener {
                onRemove()
                dialog.dismiss()
            }
        }

        fun updateUI() {
            binding.layoutPresetChips.removeAllViews()

            when (activeMode) {
                DiscountMode.PERCENT -> {
                    binding.tvInputLabel.text = "Percentage Off"
                    binding.tvInputPrefix.visibility = View.GONE
                    binding.tvInputSuffix.visibility = View.VISIBLE
                    binding.tvInputSuffix.text = "%"

                    val presets = listOf(5, 10, 15, 20, 30, 50)
                    presets.forEach { pct ->
                        val chip = createPresetChip(context, "$pct%") {
                            binding.etInputValue.setText(pct.toString())
                            binding.etInputValue.setSelection(binding.etInputValue.text.length)
                        }
                        binding.layoutPresetChips.addView(chip)
                    }
                }
                DiscountMode.FIXED_RM -> {
                    binding.tvInputLabel.text = "Fixed Deduction"
                    binding.tvInputPrefix.visibility = View.VISIBLE
                    binding.tvInputPrefix.text = "RM "
                    binding.tvInputSuffix.visibility = View.GONE

                    val presets = listOf("1.00", "2.00", "3.00", "5.00", "10.00")
                    presets.forEach { rm ->
                        val chip = createPresetChip(context, "RM $rm") {
                            binding.etInputValue.setText(rm)
                            binding.etInputValue.setSelection(binding.etInputValue.text.length)
                        }
                        binding.layoutPresetChips.addView(chip)
                    }
                }
                DiscountMode.TARGET_TOTAL -> {
                    // Hidden for items
                }
            }
        }

        fun updatePreview() {
            val typedValue = binding.etInputValue.text.toString().toDoubleOrNull() ?: 0.0

            val (discountAmt, effectiveAmt) = when (activeMode) {
                DiscountMode.PERCENT -> {
                    val rate = typedValue / 100.0
                    val disc = roundCurrency(baseAmount * rate)
                    disc to max(0.0, roundCurrency(baseAmount - disc))
                }
                DiscountMode.FIXED_RM -> {
                    val disc = kotlin.math.min(baseAmount, roundCurrency(typedValue))
                    disc to max(0.0, roundCurrency(baseAmount - disc))
                }
                DiscountMode.TARGET_TOTAL -> 0.0 to baseAmount
            }

            binding.tvPreviewDiscountAmount.text = String.format("-RM %.2f", discountAmt)
            binding.tvPreviewEffectiveAmount.text = String.format("RM %.2f", effectiveAmt)
        }

        // Toggle listener
        binding.toggleDiscountType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTypePercent -> activeMode = DiscountMode.PERCENT
                    R.id.btnTypeFixed -> activeMode = DiscountMode.FIXED_RM
                }
                updateUI()
                updatePreview()
            }
        }

        binding.etInputValue.doAfterTextChanged {
            updatePreview()
        }

        // Initial setup
        if (item.discountPercent != null) {
            binding.toggleDiscountType.check(R.id.btnTypePercent)
            val pctVal = (item.discountPercent * 100).toInt()
            binding.etInputValue.setText(pctVal.toString())
        } else if (item.discountFixed != null) {
            binding.toggleDiscountType.check(R.id.btnTypeFixed)
            binding.etInputValue.setText(String.format("%.2f", item.discountFixed))
        } else {
            binding.toggleDiscountType.check(R.id.btnTypePercent)
            binding.etInputValue.setText("10")
        }

        updateUI()
        updatePreview()

        binding.btnApplyDiscount.setOnClickListener {
            val typedValue = binding.etInputValue.text.toString().toDoubleOrNull() ?: 0.0
            if (typedValue <= 0.0) {
                onRemove()
                dialog.dismiss()
                return@setOnClickListener
            }

            val updatedItem = when (activeMode) {
                DiscountMode.PERCENT -> {
                    item.copy(
                        discountPercent = typedValue / 100.0,
                        discountFixed = null
                    )
                }
                DiscountMode.FIXED_RM -> {
                    item.copy(
                        discountPercent = null,
                        discountFixed = roundCurrency(typedValue)
                    )
                }
                DiscountMode.TARGET_TOTAL -> item
            }

            onApply(updatedItem)
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Show discount picker for the entire bill / order.
     * Supports Percent, Fixed Voucher, and Target Flat Total ("Push down to literal number").
     */
    fun showForOrder(
        context: Context,
        currentSubtotal: Double,
        existingModifier: BillModifier?,
        onApply: (BillModifier) -> Unit,
        onRemove: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = SheetDiscountPickerBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.tvSheetTitle.text = "Order Discount"
        binding.tvSheetSubtitle.text = "Apply bill-level voucher, percentage, or push down to flat total"
        binding.btnTypeTarget.visibility = View.VISIBLE

        var activeMode = when (existingModifier?.type) {
            ModifierType.DISCOUNT_PERCENT -> DiscountMode.PERCENT
            ModifierType.DISCOUNT_FIXED -> DiscountMode.FIXED_RM
            ModifierType.DISCOUNT_TARGET -> DiscountMode.TARGET_TOTAL
            else -> DiscountMode.PERCENT
        }

        binding.tvPreviewOriginalLabel.text = "Current Bill Subtotal"
        binding.tvPreviewOriginalAmount.text = String.format("RM %.2f", currentSubtotal)

        if (existingModifier != null) {
            binding.btnRemoveDiscount.visibility = View.VISIBLE
            binding.btnRemoveDiscount.setOnClickListener {
                onRemove()
                dialog.dismiss()
            }
        }

        fun updateUI() {
            binding.layoutPresetChips.removeAllViews()

            when (activeMode) {
                DiscountMode.PERCENT -> {
                    binding.tvInputLabel.text = "Bill Percentage Off"
                    binding.tvInputPrefix.visibility = View.GONE
                    binding.tvInputSuffix.visibility = View.VISIBLE
                    binding.tvInputSuffix.text = "%"

                    val presets = listOf(5, 10, 15, 20, 50)
                    presets.forEach { pct ->
                        val chip = createPresetChip(context, "$pct%") {
                            binding.etInputValue.setText(pct.toString())
                            binding.etInputValue.setSelection(binding.etInputValue.text.length)
                        }
                        binding.layoutPresetChips.addView(chip)
                    }
                }
                DiscountMode.FIXED_RM -> {
                    binding.tvInputLabel.text = "Fixed Bill Voucher"
                    binding.tvInputPrefix.visibility = View.VISIBLE
                    binding.tvInputPrefix.text = "RM "
                    binding.tvInputSuffix.visibility = View.GONE

                    val presets = listOf("5.00", "10.00", "15.00", "20.00", "50.00")
                    presets.forEach { rm ->
                        val chip = createPresetChip(context, "RM $rm") {
                            binding.etInputValue.setText(rm)
                            binding.etInputValue.setSelection(binding.etInputValue.text.length)
                        }
                        binding.layoutPresetChips.addView(chip)
                    }
                }
                DiscountMode.TARGET_TOTAL -> {
                    binding.tvInputLabel.text = "Target Grand Total (Literal Number)"
                    binding.tvInputPrefix.visibility = View.VISIBLE
                    binding.tvInputPrefix.text = "RM "
                    binding.tvInputSuffix.visibility = View.GONE

                    // Presets: nearest lower integers, e.g. if 54.30 -> 50.00, 45.00, 40.00
                    val nearest10 = (kotlin.math.floor(currentSubtotal / 10.0) * 10.0)
                    val nearest5 = (kotlin.math.floor(currentSubtotal / 5.0) * 5.0)
                    val targetPresets = linkedSetOf<Double>()
                    if (nearest5 < currentSubtotal && nearest5 > 0) targetPresets.add(nearest5)
                    if (nearest10 < currentSubtotal && nearest10 > 0) targetPresets.add(nearest10)
                    if (nearest10 - 10.0 > 0) targetPresets.add(nearest10 - 10.0)
                    if (nearest10 - 20.0 > 0) targetPresets.add(nearest10 - 20.0)

                    targetPresets.forEach { target ->
                        val chip = createPresetChip(context, String.format("RM %.2f", target)) {
                            binding.etInputValue.setText(String.format("%.2f", target))
                            binding.etInputValue.setSelection(binding.etInputValue.text.length)
                        }
                        binding.layoutPresetChips.addView(chip)
                    }
                }
            }
        }

        fun updatePreview() {
            val typedValue = binding.etInputValue.text.toString().toDoubleOrNull() ?: 0.0

            val (discountAmt, effectiveAmt) = when (activeMode) {
                DiscountMode.PERCENT -> {
                    val rate = typedValue / 100.0
                    val disc = roundCurrency(currentSubtotal * rate)
                    disc to max(0.0, roundCurrency(currentSubtotal - disc))
                }
                DiscountMode.FIXED_RM -> {
                    val disc = kotlin.math.min(currentSubtotal, roundCurrency(typedValue))
                    disc to max(0.0, roundCurrency(currentSubtotal - disc))
                }
                DiscountMode.TARGET_TOTAL -> {
                    val target = roundCurrency(typedValue)
                    if (target > 0.0 && target < currentSubtotal) {
                        val disc = roundCurrency(currentSubtotal - target)
                        disc to target
                    } else {
                        0.0 to currentSubtotal
                    }
                }
            }

            binding.tvPreviewDiscountAmount.text = String.format("-RM %.2f", discountAmt)
            binding.tvPreviewEffectiveAmount.text = String.format("RM %.2f", effectiveAmt)
        }

        binding.toggleDiscountType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTypePercent -> activeMode = DiscountMode.PERCENT
                    R.id.btnTypeFixed -> activeMode = DiscountMode.FIXED_RM
                    R.id.btnTypeTarget -> activeMode = DiscountMode.TARGET_TOTAL
                }
                updateUI()
                updatePreview()
            }
        }

        binding.etInputValue.doAfterTextChanged {
            updatePreview()
        }

        // Initial setup from existingModifier
        if (existingModifier != null) {
            when (existingModifier.type) {
                ModifierType.DISCOUNT_PERCENT -> {
                    binding.toggleDiscountType.check(R.id.btnTypePercent)
                    val pct = ((existingModifier.rate ?: 0.0) * 100).toInt()
                    binding.etInputValue.setText(pct.toString())
                }
                ModifierType.DISCOUNT_FIXED -> {
                    binding.toggleDiscountType.check(R.id.btnTypeFixed)
                    binding.etInputValue.setText(String.format("%.2f", existingModifier.fixedAmount ?: 0.0))
                }
                ModifierType.DISCOUNT_TARGET -> {
                    binding.toggleDiscountType.check(R.id.btnTypeTarget)
                    binding.etInputValue.setText(String.format("%.2f", existingModifier.targetTotal ?: 0.0))
                }
                else -> {
                    binding.toggleDiscountType.check(R.id.btnTypePercent)
                    binding.etInputValue.setText("10")
                }
            }
        } else {
            binding.toggleDiscountType.check(R.id.btnTypePercent)
            binding.etInputValue.setText("10")
        }

        updateUI()
        updatePreview()

        binding.btnApplyDiscount.setOnClickListener {
            val typedValue = binding.etInputValue.text.toString().toDoubleOrNull() ?: 0.0
            if (typedValue <= 0.0) {
                onRemove()
                dialog.dismiss()
                return@setOnClickListener
            }

            val modifier = when (activeMode) {
                DiscountMode.PERCENT -> {
                    val rate = typedValue / 100.0
                    BillModifier(
                        name = "Discount (${typedValue.toInt()}%)",
                        type = ModifierType.DISCOUNT_PERCENT,
                        rate = rate
                    )
                }
                DiscountMode.FIXED_RM -> {
                    val amount = roundCurrency(typedValue)
                    BillModifier(
                        name = "Voucher (-RM ${String.format("%.2f", amount)})",
                        type = ModifierType.DISCOUNT_FIXED,
                        fixedAmount = amount
                    )
                }
                DiscountMode.TARGET_TOTAL -> {
                    val target = roundCurrency(typedValue)
                    BillModifier(
                        name = "Target Total (RM ${String.format("%.2f", target)})",
                        type = ModifierType.DISCOUNT_TARGET,
                        targetTotal = target
                    )
                }
            }

            onApply(modifier)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun createPresetChip(context: Context, text: String, onClick: () -> Unit): View {
        val chip = TextView(context).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_qty_badge)
            setPadding(24, 12, 24, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 16
        }
        chip.layoutParams = params
        return chip
    }

    private fun roundCurrency(amount: Double): Double {
        return BigDecimal.valueOf(amount)
            .setScale(2, RoundingMode.HALF_UP)
            .toDouble()
    }
}
