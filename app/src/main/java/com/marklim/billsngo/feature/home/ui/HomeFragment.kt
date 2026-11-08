package com.marklim.billsngo.feature.home.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.marklim.billsngo.R
import com.marklim.billsngo.databinding.FragmentHomeBinding
import com.marklim.billsngo.databinding.ItemRecentExpenseBinding
import com.marklim.billsngo.feature.bill_entry.ui.BillEntryActivity

/**
 * Feature: Home Dashboard
 * Visual Identity: Titanium Dark & Cyber Lime
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private data class RecentExpenseMock(
        val title: String,
        val categoryAndDate: String,
        val formattedAmount: String,
        val badgeBgColorRes: Int,
        val badgeIconColorRes: Int
    )

    private val mockExpenses = listOf(
        RecentExpenseMock(
            title = "Starbucks Reserve",
            categoryAndDate = "Food & Dining • Today, 2:15 PM",
            formattedAmount = "-RM 26.50",
            badgeBgColorRes = R.color.badge_food_bg,
            badgeIconColorRes = R.color.badge_food_icon
        ),
        RecentExpenseMock(
            title = "Lotus's Hypermarket",
            categoryAndDate = "Groceries • Yesterday, 6:40 PM",
            formattedAmount = "-RM 142.80",
            badgeBgColorRes = R.color.badge_groceries_bg,
            badgeIconColorRes = R.color.badge_groceries_icon
        ),
        RecentExpenseMock(
            title = "Shell Petrol Station",
            categoryAndDate = "Transport • 23 Oct 2026",
            formattedAmount = "-RM 50.00",
            badgeBgColorRes = R.color.badge_transport_bg,
            badgeIconColorRes = R.color.badge_transport_icon
        ),
        RecentExpenseMock(
            title = "Uniqlo Mid Valley",
            categoryAndDate = "Shopping • 21 Oct 2026",
            formattedAmount = "-RM 129.90",
            badgeBgColorRes = R.color.badge_shopping_bg,
            badgeIconColorRes = R.color.badge_shopping_icon
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupThemeColors()
        setupQuickActions()
        setupTopBarActions()
        setupFilterChips()
        populateRecentExpenses()
    }

    private fun setupThemeColors() {
        val context = requireContext()
        val bgTitanium = ContextCompat.getColor(context, R.color.bg_titanium)
        val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
        val borderHairline = ContextCompat.getColor(context, R.color.border_hairline)
        val surfaceTonal = ContextCompat.getColor(context, R.color.surface_tonal)
        val jetBlack = ContextCompat.getColor(context, R.color.chassis_jet_black)
        val cyberLime = ContextCompat.getColor(context, R.color.accent_cyber_lime)

        // Root Background
        binding.root.setBackgroundColor(bgTitanium)

        // Header
        binding.tvHeaderLabel.setTextColor(textSecondary)
        binding.tvHeaderAmount.setTextColor(textPrimary)

        // Top Bar Icons
        binding.btnNotifications.setColorFilter(textPrimary)
        binding.btnMoreMenu.setColorFilter(textPrimary)

        // Action Hardware Dock: All 4 tiles in Matte Jet Black chassis
        val actionTiles = listOf(
            binding.cardQuickSnapIcon to binding.ivQuickSnapIcon,
            binding.cardDashboardIcon to binding.ivDashboardIcon,
            binding.cardHistoryIcon to binding.ivHistoryIcon,
            binding.cardGoalsIcon to binding.ivGoalsIcon
        )
        for ((card, icon) in actionTiles) {
            card.setCardBackgroundColor(jetBlack)
            card.strokeColor = borderHairline
            icon.setColorFilter(cyberLime)
        }

        // Search Bar
        binding.cardSearch.setCardBackgroundColor(surfaceTonal)
        binding.tvSearchHint.setTextColor(textSecondary)
    }

    private fun setupFilterChips() {
        val context = requireContext()
        val jetBlack = ContextCompat.getColor(context, R.color.chassis_jet_black)
        val cyberLime = ContextCompat.getColor(context, R.color.accent_cyber_lime)
        val surfaceTonal = ContextCompat.getColor(context, R.color.surface_tonal)
        val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)
        val borderHairline = ContextCompat.getColor(context, R.color.border_hairline)

        val chips = listOf(
            binding.chipAll,
            binding.chipFood,
            binding.chipGroceries,
            binding.chipBills,
            binding.chipShopping
        )

        for (chip in chips) {
            val isChecked = chip.isChecked
            val chipBg = if (isChecked) jetBlack else surfaceTonal
            val chipTextColor = if (isChecked) cyberLime else textSecondary

            chip.chipBackgroundColor = ColorStateList.valueOf(chipBg)
            chip.setTextColor(chipTextColor)
            chip.chipStrokeColor = ColorStateList.valueOf(borderHairline)
            chip.chipStrokeWidth = 2f

            chip.setOnCheckedChangeListener { _, _ ->
                setupFilterChips()
            }
        }
    }

    private fun populateRecentExpenses() {
        val context = requireContext()
        val surfaceWhite = ContextCompat.getColor(context, R.color.surface_card)
        val borderHairline = ContextCompat.getColor(context, R.color.border_hairline)
        val textPrimary = ContextCompat.getColor(context, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(context, R.color.text_secondary)

        binding.llRecentExpensesContainer.removeAllViews()

        for (expense in mockExpenses) {
            val rowBinding = ItemRecentExpenseBinding.inflate(
                layoutInflater,
                binding.llRecentExpensesContainer,
                false
            )

            rowBinding.root.setCardBackgroundColor(surfaceWhite)
            rowBinding.root.strokeColor = borderHairline
            rowBinding.tvExpenseTitle.text = expense.title
            rowBinding.tvExpenseTitle.setTextColor(textPrimary)
            rowBinding.tvExpenseCategoryDate.text = expense.categoryAndDate
            rowBinding.tvExpenseCategoryDate.setTextColor(textSecondary)
            rowBinding.tvExpenseAmount.text = expense.formattedAmount
            rowBinding.tvExpenseAmount.setTextColor(textPrimary)
            rowBinding.ivChevron.setColorFilter(textSecondary)

            // Category Icon Badge
            val badgeBg = ContextCompat.getColor(context, expense.badgeBgColorRes)
            val badgeIcon = ContextCompat.getColor(context, expense.badgeIconColorRes)
            rowBinding.cardIconContainer.setCardBackgroundColor(badgeBg)
            rowBinding.ivExpenseIcon.setColorFilter(badgeIcon)

            rowBinding.root.setOnClickListener {
                Toast.makeText(context, "Clicked on: ${expense.title}", Toast.LENGTH_SHORT).show()
            }

            binding.llRecentExpensesContainer.addView(rowBinding.root)
        }
    }

    private fun setupQuickActions() {
        binding.btnQuickSnap.setOnClickListener {
            val intent = Intent(requireContext(), BillEntryActivity::class.java)
            startActivity(intent)
        }

        binding.btnDashboard.setOnClickListener {
            Toast.makeText(requireContext(), "Dashboard coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.btnHistory.setOnClickListener {
            Toast.makeText(requireContext(), "History coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.btnGoals.setOnClickListener {
            Toast.makeText(requireContext(), "Goals coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.cardSearch.setOnClickListener {
            Toast.makeText(requireContext(), "Search coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTopBarActions() {
        binding.btnNotifications.setOnClickListener {
            Toast.makeText(requireContext(), "Notifications", Toast.LENGTH_SHORT).show()
        }

        binding.btnMoreMenu.setOnClickListener {
            Toast.makeText(requireContext(), "More options", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
