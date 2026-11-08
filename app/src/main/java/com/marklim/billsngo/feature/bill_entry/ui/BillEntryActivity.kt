package com.marklim.billsngo.feature.bill_entry.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScanner
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.marklim.billsngo.R
import com.marklim.billsngo.core.domain.BillMathEngine
import com.marklim.billsngo.core.model.BillCalculationResult
import com.marklim.billsngo.core.model.BillItem
import com.marklim.billsngo.core.model.BillModifier
import com.marklim.billsngo.core.model.ModifierType
import com.marklim.billsngo.core.model.ParsedBill
import com.marklim.billsngo.databinding.ActivityMainBinding
import com.marklim.billsngo.databinding.ItemReceiptRowBinding
import com.marklim.billsngo.databinding.ItemSummaryRowBinding
import com.marklim.billsngo.feature.bill_entry.domain.BillParser
import com.marklim.billsngo.feature.bill_entry.domain.BillTextReconstructor

/**
 * Feature: Bill Entry / Quick Snap Review
 * Visual Identity: Titanium Dark & Cyber Lime
 * Editing Paradigm: Living Document (Inline Tap-to-Edit with Tap-Outside-to-Dismiss)
 */
class BillEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scanner: GmsDocumentScanner
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var textRecognizer: TextRecognizer

    private var rawExtractedText: String = ""
    private var reconstructedRowText: String = ""
    private var parsedBill: ParsedBill = ParsedBill()

    private val currentItems = mutableListOf<BillItem>()
    private val currentModifiers = mutableListOf<BillModifier>()
    private var ocrExplicitTotal: Double? = null

    private var scannedImageUri: Uri? = null
    private var hasLaunchedInitialScan = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigationActions()
        setupScannerLauncher()
        setupScanner()
        setupTextRecognizer()
        setupSegmentedToggleGroup()
        setupEnlargePreviewAction()
        updateDisplayedView(binding.toggleGroupView.checkedButtonId)

        // Dismiss keyboard and clear focus when scrolling the receipt list
        binding.nestedScrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            clearActiveFocusAndKeyboard()
        }

        // Save / Confirm Action
        binding.btnConfirmBill.setOnClickListener {
            val calcResult = BillMathEngine.calculate(currentItems, currentModifiers, ocrExplicitTotal)
            if (currentItems.isEmpty() && calcResult.grandTotal == 0.0) {
                Toast.makeText(this, "Scan a receipt first before saving", Toast.LENGTH_SHORT).show()
                startScanning()
            } else {
                Toast.makeText(this, "Bill saved successfully! (${currentItems.size} items • RM ${calcResult.formattedGrandTotal})", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.btnRetake.setOnClickListener {
            startScanning()
        }

        // Auto-launch scanner immediately when entering Quick Snap
        if (savedInstanceState == null && !hasLaunchedInitialScan) {
            hasLaunchedInitialScan = true
            startScanning()
        }
    }

    /**
     * Tap-Outside-to-Unfocus:
     * Clears focus on any active EditText and dismisses the keyboard
     * when the user taps anywhere outside the active row.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is EditText) {
                var container: View? = v
                while (container != null && container.id != R.id.rowRoot && container.id != android.R.id.content) {
                    container = container.parent as? View
                }
                val targetView = if (container?.id == R.id.rowRoot) container else v
                val outRect = Rect()
                targetView.getGlobalVisibleRect(outRect)

                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    clearActiveFocusAndKeyboard()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun clearActiveFocusAndKeyboard() {
        val v = currentFocus
        if (v is EditText) {
            v.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    private fun setupNavigationActions() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun setupEnlargePreviewAction() {
        binding.cardPreviewThumbnail.setOnClickListener {
            val uri = scannedImageUri
            if (uri != null) {
                showFullscreenReceiptDialog(uri)
            } else {
                Toast.makeText(this, "No receipt scanned yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFullscreenReceiptDialog(imageUri: Uri) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_receipt_fullscreen)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val ivFull = dialog.findViewById<ImageView>(R.id.ivFullDocument)
        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseDialog)

        ivFull.setImageURI(imageUri)
        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupSegmentedToggleGroup() {
        binding.toggleGroupView.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                updateDisplayedView(checkedId)
            }
        }
    }

    private fun setupScannerLauncher() {
        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                handleScanResult(scanResult)
            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                if (currentItems.isEmpty()) {
                    finish()
                }
            }
        }
    }

    private fun setupScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        scanner = GmsDocumentScanning.getClient(options)
    }

    private fun setupTextRecognizer() {
        textRecognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
    }

    private fun startScanning() {
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { e ->
                val errorMsg = "Scanner error: ${e.localizedMessage}"
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            }
    }

    private fun handleScanResult(scanResult: GmsDocumentScanningResult?) {
        if (scanResult == null) return

        val pages = scanResult.pages
        if (!pages.isNullOrEmpty()) {
            val firstPageUri = pages[0].imageUri
            scannedImageUri = firstPageUri
            binding.ivScannedDocument.setImageURI(firstPageUri)
            runTextRecognition(firstPageUri)
        }
    }

    private fun runTextRecognition(imageUri: Uri) {
        try {
            val inputImage = InputImage.fromFilePath(this, imageUri)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    rawExtractedText = visionText.text
                    reconstructedRowText = BillTextReconstructor.reconstruct(visionText)
                    parsedBill = BillParser.parse(reconstructedRowText)

                    currentItems.clear()
                    currentItems.addAll(parsedBill.items)

                    currentModifiers.clear()
                    currentModifiers.addAll(parsedBill.detectedModifiers)

                    ocrExplicitTotal = parsedBill.explicitTotal

                    if (rawExtractedText.isNotBlank()) {
                        renderParsedBill()
                        updateDisplayedView(binding.toggleGroupView.checkedButtonId)
                    } else {
                        binding.tvHeroTotal.text = "RM 0.00"
                        binding.tvItemCountBadge.text = "No text detected"
                        binding.tvExtractedText.text = "(No text detected)"
                    }
                }
                .addOnFailureListener { e ->
                    val errorMsg = "Text recognition failed: ${e.localizedMessage}"
                    binding.tvExtractedText.text = errorMsg
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                }
        } catch (e: Exception) {
            val errorMsg = "Failed to load image: ${e.localizedMessage}"
            binding.tvExtractedText.text = errorMsg
        }
    }

    private fun renderParsedBill() {
        binding.llItemsContainer.removeAllViews()

        // 1. Update Hero Total & Item Count (driven by BillMathEngine)
        refreshTotalsAndBadge()

        // 2. Render Items
        if (currentItems.isEmpty()) {
            binding.tvEmptyItems.visibility = View.VISIBLE
            binding.tvEmptyItems.text = "No item rows detected.\nTap 'Add missing item' or check 'Rows'."
            binding.btnAddItem.visibility = View.VISIBLE
        } else {
            binding.tvEmptyItems.visibility = View.GONE
            binding.btnAddItem.visibility = View.VISIBLE

            for (i in currentItems.indices) {
                val item = currentItems[i]
                val rowBinding = ItemReceiptRowBinding.inflate(layoutInflater, binding.llItemsContainer, false)

                rowBinding.tvItemQty.text = "${item.quantity}x"
                rowBinding.etQtyValue.setText(item.quantity.toString())
                rowBinding.etItemName.setText(item.name)
                rowBinding.etItemAmount.setText(item.formattedAmount)

                // Item-Level Discount display
                if (item.discountAmount > 0) {
                    rowBinding.layoutDiscountPill.visibility = View.VISIBLE
                    rowBinding.tvDiscountText.text = "${item.discountLabel} (Save RM ${String.format("%.2f", item.discountAmount)})"
                    rowBinding.tvItemEffectiveAmount.visibility = View.VISIBLE
                    rowBinding.tvItemEffectiveAmount.text = "RM ${item.formattedEffectiveAmount} net"
                } else {
                    rowBinding.layoutDiscountPill.visibility = View.GONE
                    rowBinding.tvItemEffectiveAmount.visibility = View.GONE
                }

                // 1-Tap remove item discount
                rowBinding.btnRemoveItemDiscount.setOnClickListener {
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        currentItems[index] = currentItems[index].copy(discountPercent = null, discountFixed = null)
                        renderParsedBill()
                    }
                }

                // Tap discount pill to edit
                rowBinding.layoutDiscountPill.setOnClickListener {
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        DiscountBottomSheetHelper.showForItem(
                            context = this@BillEntryActivity,
                            item = currentItems[index],
                            onApply = { updatedItem ->
                                currentItems[index] = updatedItem
                                renderParsedBill()
                            },
                            onRemove = {
                                currentItems[index] = currentItems[index].copy(discountPercent = null, discountFixed = null)
                                renderParsedBill()
                            }
                        )
                    }
                }

                // Focus styling reveals active container, micro stepper, discount & delete actions
                val setRowActive = { active: Boolean ->
                    if (active) {
                        rowBinding.rowRoot.setBackgroundResource(R.drawable.bg_receipt_row_active)
                        rowBinding.tvItemQty.visibility = View.GONE
                        rowBinding.layoutQtyStepper.visibility = View.VISIBLE
                        rowBinding.layoutActiveActions.visibility = View.VISIBLE
                    } else {
                        rowBinding.rowRoot.setBackgroundResource(android.R.color.transparent)
                        rowBinding.tvItemQty.visibility = View.VISIBLE
                        rowBinding.layoutQtyStepper.visibility = View.GONE
                        rowBinding.layoutActiveActions.visibility = View.GONE
                        val amt = rowBinding.etItemAmount.text.toString().toDoubleOrNull() ?: 0.0
                        rowBinding.etItemAmount.setText(String.format("%.2f", amt))
                    }
                }

                val focusChangeListener = View.OnFocusChangeListener { _, _ ->
                    val isAnyFocused = rowBinding.etItemName.hasFocus() ||
                            rowBinding.etItemAmount.hasFocus() ||
                            rowBinding.etQtyValue.hasFocus()
                    setRowActive(isAnyFocused)
                }

                rowBinding.etItemName.onFocusChangeListener = focusChangeListener
                rowBinding.etItemAmount.onFocusChangeListener = focusChangeListener
                rowBinding.etQtyValue.onFocusChangeListener = focusChangeListener

                // Tap normal qty pill to open micro stepper
                rowBinding.tvItemQty.setOnClickListener {
                    setRowActive(true)
                    rowBinding.etQtyValue.requestFocus()
                }

                // Stepper: Minus (−)
                rowBinding.btnQtyMinus.setOnClickListener {
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        val currentQty = currentItems[index].quantity
                        if (currentQty > 1) {
                            val newQty = currentQty - 1
                            currentItems[index] = currentItems[index].copy(quantity = newQty)
                            rowBinding.etQtyValue.setText(newQty.toString())
                            rowBinding.tvItemQty.text = "${newQty}x"
                            renderSummaries()
                            refreshTotalsAndBadge()
                        }
                    }
                }

                // Stepper: Plus (+)
                rowBinding.btnQtyPlus.setOnClickListener {
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        val currentQty = currentItems[index].quantity
                        val newQty = currentQty + 1
                        currentItems[index] = currentItems[index].copy(quantity = newQty)
                        rowBinding.etQtyValue.setText(newQty.toString())
                        rowBinding.tvItemQty.text = "${newQty}x"
                        renderSummaries()
                        refreshTotalsAndBadge()
                    }
                }

                // Stepper: Direct numeric typing
                rowBinding.etQtyValue.doAfterTextChanged { s ->
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        val typedQty = s?.toString()?.toIntOrNull()
                        if (typedQty != null && typedQty in 1..999) {
                            currentItems[index] = currentItems[index].copy(quantity = typedQty)
                            rowBinding.tvItemQty.text = "${typedQty}x"
                            renderSummaries()
                            refreshTotalsAndBadge()
                        }
                    }
                }

                // Live updates on Name change
                rowBinding.etItemName.doAfterTextChanged { s ->
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        currentItems[index] = currentItems[index].copy(name = s?.toString()?.trim() ?: "")
                    }
                }

                // Live updates on Amount change (reactively recomputes summaries & grand total)
                rowBinding.etItemAmount.doAfterTextChanged { s ->
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        val newAmt = s?.toString()?.toDoubleOrNull() ?: 0.0
                        currentItems[index] = currentItems[index].copy(amount = newAmt)
                        // Update effective amount text if discount is active
                        if (currentItems[index].discountAmount > 0) {
                            rowBinding.tvItemEffectiveAmount.text = "RM ${currentItems[index].formattedEffectiveAmount} net"
                            rowBinding.tvDiscountText.text = "${currentItems[index].discountLabel} (Save RM ${String.format("%.2f", currentItems[index].discountAmount)})"
                        }
                        renderSummaries()
                        refreshTotalsAndBadge()
                    }
                }

                // Item Discount button in active actions
                rowBinding.btnItemDiscount.setOnClickListener {
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        DiscountBottomSheetHelper.showForItem(
                            context = this@BillEntryActivity,
                            item = currentItems[index],
                            onApply = { updatedItem ->
                                currentItems[index] = updatedItem
                                renderParsedBill()
                            },
                            onRemove = {
                                currentItems[index] = currentItems[index].copy(discountPercent = null, discountFixed = null)
                                renderParsedBill()
                            }
                        )
                    }
                }

                // Delete item
                rowBinding.btnDeleteItem.setOnClickListener {
                    val index = binding.llItemsContainer.indexOfChild(rowBinding.root)
                    if (index in currentItems.indices) {
                        currentItems.removeAt(index)
                        binding.llItemsContainer.removeView(rowBinding.root)
                        renderSummaries()
                        refreshTotalsAndBadge()
                        if (currentItems.isEmpty()) {
                            binding.tvEmptyItems.visibility = View.VISIBLE
                        }
                    }
                }

                binding.llItemsContainer.addView(rowBinding.root)
            }
        }

        // Setup "+ Add Missing Item" button
        binding.btnAddItem.setOnClickListener {
            val newItem = BillItem(quantity = 1, name = "", amount = 0.0)
            currentItems.add(newItem)
            renderParsedBill()
            val lastChild = binding.llItemsContainer.getChildAt(binding.llItemsContainer.childCount - 1)
            val etName = lastChild?.findViewById<EditText>(R.id.etItemName)
            etName?.requestFocus()
        }

        // 3. Render Summaries driven by Math Engine
        renderSummaries()
    }

    /**
     * Renders mathematical breakdown: Subtotal -> Modifiers (Taxes/Charges) -> Rounding -> Grand Total.
     * Everything is computed reactively from currentItems and currentModifiers.
     */
    private fun renderSummaries() {
        binding.llSummariesContainer.removeAllViews()

        val calcResult = BillMathEngine.calculate(currentItems, currentModifiers, ocrExplicitTotal)

        if (currentItems.isEmpty()) {
            binding.dividerSummary.visibility = View.GONE
            binding.scrollQuickModifiers.visibility = View.GONE
            return
        }

        binding.dividerSummary.visibility = View.VISIBLE
        binding.scrollQuickModifiers.visibility = View.VISIBLE

        // 1. Subtotal Row (Auto-computed from items)
        if (calcResult.itemDiscountsTotal > 0.0) {
            val rawSubtotalBinding = ItemSummaryRowBinding.inflate(layoutInflater, binding.llSummariesContainer, false)
            rawSubtotalBinding.tvSummaryLabel.text = "Subtotal (Raw)"
            rawSubtotalBinding.tvSummaryAmount.text = "RM ${calcResult.formattedRawSubtotal}"
            binding.llSummariesContainer.addView(rawSubtotalBinding.root)

            val itemDiscBinding = ItemSummaryRowBinding.inflate(layoutInflater, binding.llSummariesContainer, false)
            itemDiscBinding.tvSummaryLabel.text = "Item Discounts"
            itemDiscBinding.tvSummaryLabel.setTextColor(getColor(R.color.badge_food_icon))
            itemDiscBinding.tvSummaryAmount.text = "-RM ${String.format("%.2f", calcResult.itemDiscountsTotal)}"
            itemDiscBinding.tvSummaryAmount.setTextColor(getColor(R.color.badge_food_icon))
            binding.llSummariesContainer.addView(itemDiscBinding.root)
        } else {
            val subtotalBinding = ItemSummaryRowBinding.inflate(layoutInflater, binding.llSummariesContainer, false)
            subtotalBinding.tvSummaryLabel.text = "Subtotal"
            subtotalBinding.tvSummaryAmount.text = "RM ${calcResult.formattedSubtotal}"
            binding.llSummariesContainer.addView(subtotalBinding.root)
        }

        // 2. Modifiers Breakdown (Taxes, Service Charges, Discounts, Rounding)
        for (breakdown in calcResult.modifierBreakdowns) {
            val modBinding = ItemSummaryRowBinding.inflate(layoutInflater, binding.llSummariesContainer, false)
            modBinding.tvSummaryLabel.text = breakdown.modifier.name

            val prefix = if (breakdown.amount < 0) "-RM " else "RM "
            val absFormatted = String.format("%.2f", kotlin.math.abs(breakdown.amount))
            modBinding.tvSummaryAmount.text = "$prefix$absFormatted"

            val isDiscount = breakdown.modifier.type == ModifierType.DISCOUNT_PERCENT ||
                    breakdown.modifier.type == ModifierType.DISCOUNT_FIXED ||
                    breakdown.modifier.type == ModifierType.DISCOUNT_TARGET

            if (isDiscount) {
                modBinding.tvSummaryLabel.setTextColor(getColor(R.color.badge_food_icon))
                modBinding.tvSummaryAmount.setTextColor(getColor(R.color.badge_food_icon))

                // Tap discount summary row to edit
                modBinding.root.isClickable = true
                modBinding.root.setOnClickListener {
                    DiscountBottomSheetHelper.showForOrder(
                        context = this@BillEntryActivity,
                        currentSubtotal = calcResult.subtotal,
                        existingModifier = breakdown.modifier,
                        onApply = { mod ->
                            currentModifiers.removeAll {
                                it.type == ModifierType.DISCOUNT_PERCENT ||
                                        it.type == ModifierType.DISCOUNT_FIXED ||
                                        it.type == ModifierType.DISCOUNT_TARGET
                            }
                            currentModifiers.add(mod)
                            renderSummaries()
                            refreshTotalsAndBadge()
                        },
                        onRemove = {
                            currentModifiers.remove(breakdown.modifier)
                            renderSummaries()
                            refreshTotalsAndBadge()
                        }
                    )
                }
            }

            // Show 1-tap remove button for optional modifiers
            modBinding.btnRemoveModifier.visibility = View.VISIBLE
            modBinding.btnRemoveModifier.setOnClickListener {
                currentModifiers.remove(breakdown.modifier)
                renderSummaries()
                refreshTotalsAndBadge()
            }

            binding.llSummariesContainer.addView(modBinding.root)
        }

        // 3. Grand Total Row (Auto-computed)
        val totalBinding = ItemSummaryRowBinding.inflate(layoutInflater, binding.llSummariesContainer, false)
        totalBinding.tvSummaryLabel.text = "Grand Total"
        totalBinding.tvSummaryLabel.setTypeface(null, Typeface.BOLD)
        totalBinding.tvSummaryLabel.setTextColor(getColor(R.color.text_primary))

        totalBinding.tvSummaryAmount.text = "RM ${calcResult.formattedGrandTotal}"
        totalBinding.tvSummaryAmount.setTypeface(null, Typeface.BOLD)
        totalBinding.tvSummaryAmount.setTextColor(getColor(R.color.text_primary))
        binding.llSummariesContainer.addView(totalBinding.root)

        // 4. Update Quick Modifier Chips
        setupQuickModifierChips()
    }

    private fun setupQuickModifierChips() {
        val hasSst = currentModifiers.any { it.type == ModifierType.TAX && it.isEnabled }
        val hasSvc = currentModifiers.any { it.type == ModifierType.SERVICE_CHARGE && it.isEnabled }
        val hasRounding = currentModifiers.any { it.type == ModifierType.ROUNDING && it.isEnabled }
        val activeDiscount = currentModifiers.firstOrNull {
            (it.type == ModifierType.DISCOUNT_PERCENT ||
                    it.type == ModifierType.DISCOUNT_FIXED ||
                    it.type == ModifierType.DISCOUNT_TARGET) && it.isEnabled
        }
        val hasDiscount = activeDiscount != null

        // 6% SST Chip
        binding.chipAddSst6.text = if (hasSst) "✓ 6% SST" else "+ 6% SST"
        binding.chipAddSst6.setOnClickListener {
            if (hasSst) {
                currentModifiers.removeAll { it.type == ModifierType.TAX }
            } else {
                currentModifiers.add(BillMathEngine.sst6(isCompounded = true))
            }
            renderSummaries()
            refreshTotalsAndBadge()
        }

        // 10% Service Charge Chip
        binding.chipAddSvc10.text = if (hasSvc) "✓ 10% Service" else "+ 10% Service"
        binding.chipAddSvc10.setOnClickListener {
            if (hasSvc) {
                currentModifiers.removeAll { it.type == ModifierType.SERVICE_CHARGE }
            } else {
                currentModifiers.add(BillMathEngine.serviceCharge10())
            }
            renderSummaries()
            refreshTotalsAndBadge()
        }

        // 5-sen Rounding Chip
        binding.chipAddRounding.text = if (hasRounding) "✓ Rounding" else "+ Rounding"
        binding.chipAddRounding.setOnClickListener {
            if (hasRounding) {
                currentModifiers.removeAll { it.type == ModifierType.ROUNDING }
            } else {
                currentModifiers.add(BillMathEngine.rounding5Sen())
            }
            renderSummaries()
            refreshTotalsAndBadge()
        }

        // Discount Chip (Opens bottom sheet to configure percent, fixed voucher, or target total)
        binding.chipAddDiscount.text = if (hasDiscount) "✓ Discount" else "- Discount"
        binding.chipAddDiscount.setOnClickListener {
            val calcResult = BillMathEngine.calculate(currentItems, currentModifiers, ocrExplicitTotal)
            DiscountBottomSheetHelper.showForOrder(
                context = this@BillEntryActivity,
                currentSubtotal = calcResult.subtotal,
                existingModifier = activeDiscount,
                onApply = { mod ->
                    currentModifiers.removeAll {
                        it.type == ModifierType.DISCOUNT_PERCENT ||
                                it.type == ModifierType.DISCOUNT_FIXED ||
                                it.type == ModifierType.DISCOUNT_TARGET
                    }
                    currentModifiers.add(mod)
                    renderSummaries()
                    refreshTotalsAndBadge()
                },
                onRemove = {
                    currentModifiers.removeAll {
                        it.type == ModifierType.DISCOUNT_PERCENT ||
                                it.type == ModifierType.DISCOUNT_FIXED ||
                                it.type == ModifierType.DISCOUNT_TARGET
                    }
                    renderSummaries()
                    refreshTotalsAndBadge()
                }
            )
        }
    }

    private fun refreshTotalsAndBadge() {
        val calcResult = BillMathEngine.calculate(currentItems, currentModifiers, ocrExplicitTotal)

        binding.tvHeroTotal.text = String.format("RM %.2f", calcResult.grandTotal)

        val statusText = when {
            ocrExplicitTotal != null && calcResult.isReconciled -> {
                "${currentItems.size} item(s) • ✓ Matched receipt total"
            }
            ocrExplicitTotal != null -> {
                val diff = calcResult.grandTotal - (ocrExplicitTotal ?: 0.0)
                val sign = if (diff > 0) "+" else ""
                "${currentItems.size} item(s) • Differs by ${sign}RM ${String.format("%.2f", diff)}"
            }
            else -> {
                "${currentItems.size} item(s) detected"
            }
        }
        binding.tvItemCountBadge.text = statusText
    }

    private fun updateDisplayedView(checkedButtonId: Int) {
        when (checkedButtonId) {
            R.id.btnViewParsed -> {
                binding.scrollParsedView.visibility = View.VISIBLE
                binding.scrollTextView.visibility = View.GONE
            }
            R.id.btnViewReconstructed -> {
                binding.scrollParsedView.visibility = View.GONE
                binding.scrollTextView.visibility = View.VISIBLE
                binding.tvExtractedText.text = reconstructedRowText.ifEmpty { "(No text detected)" }
            }
            R.id.btnViewRaw -> {
                binding.scrollParsedView.visibility = View.GONE
                binding.scrollTextView.visibility = View.VISIBLE
                binding.tvExtractedText.text = rawExtractedText.ifEmpty { "(No text detected)" }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
    }
}
