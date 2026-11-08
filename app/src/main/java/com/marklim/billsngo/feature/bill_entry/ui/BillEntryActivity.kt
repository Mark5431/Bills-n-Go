package com.marklim.billsngo.feature.bill_entry.ui

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import com.marklim.billsngo.core.model.ParsedBill
import com.marklim.billsngo.databinding.ActivityMainBinding
import com.marklim.billsngo.databinding.ItemReceiptRowBinding
import com.marklim.billsngo.databinding.ItemSummaryRowBinding
import com.marklim.billsngo.feature.bill_entry.domain.BillParser
import com.marklim.billsngo.feature.bill_entry.domain.BillTextReconstructor

/**
 * Feature: Bill Entry / Quick Snap Review
 * Visual Identity: Titanium Dark & Cyber Lime
 */
class BillEntryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var scanner: GmsDocumentScanner
    private lateinit var scannerLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var textRecognizer: TextRecognizer

    private var rawExtractedText: String = ""
    private var reconstructedRowText: String = ""
    private var parsedBill: ParsedBill = ParsedBill()
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

        // Save / Confirm Action
        binding.btnConfirmBill.setOnClickListener {
            if (parsedBill.items.isEmpty() && (parsedBill.explicitTotal ?: 0.0) == 0.0) {
                Toast.makeText(this, "Scan a receipt first before saving", Toast.LENGTH_SHORT).show()
                startScanning()
            } else {
                Toast.makeText(this, "Bill saved successfully!", Toast.LENGTH_SHORT).show()
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
                if (parsedBill.items.isEmpty()) {
                    finish()
                }
            } else {
                Toast.makeText(this, "Scan failed (code: ${result.resultCode})", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(1)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
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

                    if (rawExtractedText.isNotBlank()) {
                        renderParsedBill(parsedBill)
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

    private fun renderParsedBill(bill: ParsedBill) {
        binding.llItemsContainer.removeAllViews()
        binding.llSummariesContainer.removeAllViews()

        // 1. Update Hero Total & Item Count
        val displayTotal = bill.explicitTotal ?: bill.calculatedTotal
        binding.tvHeroTotal.text = String.format("RM %.2f", displayTotal)
        binding.tvItemCountBadge.text = "${bill.items.size} item(s) detected"

        // 2. Render Items
        if (bill.items.isEmpty()) {
            binding.tvEmptyItems.visibility = View.VISIBLE
            binding.tvEmptyItems.text = "No item rows could be identified.\nCheck 'Rows' or 'Raw' view."
        } else {
            binding.tvEmptyItems.visibility = View.GONE
            for (item in bill.items) {
                val rowBinding = ItemReceiptRowBinding.inflate(layoutInflater, binding.llItemsContainer, false)
                rowBinding.tvItemQty.text = "${item.quantity}x"
                rowBinding.tvItemName.text = item.name
                rowBinding.tvItemAmount.text = item.formattedAmount
                binding.llItemsContainer.addView(rowBinding.root)
            }
        }

        // 3. Render Summaries (Subtotal, Tax, Total)
        if (bill.summaries.isNotEmpty()) {
            binding.dividerSummary.visibility = View.VISIBLE
            for (summary in bill.summaries) {
                val summaryBinding = ItemSummaryRowBinding.inflate(layoutInflater, binding.llSummariesContainer, false)
                val isTotal = summary.label.contains("total", ignoreCase = true)
                summaryBinding.tvSummaryLabel.text = summary.label
                summaryBinding.tvSummaryAmount.text = summary.formattedAmount

                if (isTotal) {
                    summaryBinding.tvSummaryLabel.setTypeface(null, Typeface.BOLD)
                    summaryBinding.tvSummaryLabel.setTextColor(getColor(R.color.text_primary))
                    summaryBinding.tvSummaryAmount.setTypeface(null, Typeface.BOLD)
                    summaryBinding.tvSummaryAmount.setTextColor(getColor(R.color.text_primary))
                }
                binding.llSummariesContainer.addView(summaryBinding.root)
            }
        } else {
            binding.dividerSummary.visibility = View.GONE
        }
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
