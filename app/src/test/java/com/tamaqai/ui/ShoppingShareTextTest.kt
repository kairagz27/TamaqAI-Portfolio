package com.tamaqai.ui

import com.tamaqai.domain.model.*
import com.tamaqai.ui.screens.ShoppingShareLabels
import com.tamaqai.ui.screens.shoppingShareText
import org.junit.Assert.*
import org.junit.Test

class ShoppingShareTextTest {
    private fun labels() = ShoppingShareLabels(
        "Shopping", "quantity unknown", "unspecified addition", "check amount", "not in current plan",
        "Note", "Dish", "Servings", ShoppingCategory.entries.associateWith { it.name },
        ShoppingStatus.entries.associateWith { it.name }, RecipeUnit.entries.associateWith { it.name },
        FoodPreparation.entries.associateWith { it.name },
    )

    @Test fun exportPreservesManualItemsNotesStatusAndDishOrigins() {
        val document = ShoppingDocument("default", 2, listOf(
            ShoppingRow("rice", "Rice", "rice", RecipeUnit.Gram, 250.0,
                uses = listOf(ShoppingUse("a", "Chicken and rice", "2026-10-29", 2.0),
                    ShoppingUse("b", "Vegetable rice", "2026-10-30", 1.0)),
                category = ShoppingCategory.Grains, preparation = FoodPreparation.Raw,
                status = ShoppingStatus.Bought, note = "Choose this package", needsReview = true),
            ShoppingRow("manual", "Reusable bags", unit = RecipeUnit.Piece, quantity = 1.0, manual = true),
        ))
        val text = shoppingShareText(document, "en", labels())
        assertTrue(text.contains("[x] Rice — 250 Gram (Bought; Raw)"))
        assertTrue(text.contains("Chicken and rice"))
        assertTrue(text.contains("Vegetable rice"))
        assertTrue(text.contains("Choose this package"))
        assertTrue(text.contains("check amount"))
        assertTrue(text.contains("Reusable bags"))
    }

    @Test fun unknownQuantityIsNotExportedAsZeroAndPreparationsStayDistinct() {
        val document = ShoppingDocument("default", 0, listOf(
            ShoppingRow("raw", "Rice", "rice", RecipeUnit.Gram, null, unknownQuantity = true, preparation = FoodPreparation.Raw),
            ShoppingRow("cooked", "Rice", "rice", RecipeUnit.Gram, 150.0, preparation = FoodPreparation.Cooked),
        ))
        val text = shoppingShareText(document, "en", labels())
        assertTrue(text.contains("Rice — quantity unknown"))
        assertTrue(text.contains("150 Gram"))
        assertTrue(text.contains("Raw"))
        assertTrue(text.contains("Cooked"))
        assertFalse(text.contains("— 0 "))
    }

    @Test fun partialQuantityAndInactiveNotesRemainExplicit() {
        val document = ShoppingDocument("default", 3, listOf(
            ShoppingRow("partial", "Carrot", "carrot", RecipeUnit.Gram, 100.0, unknownQuantity = true),
            ShoppingRow("retained", "Milk", "milk", RecipeUnit.Milliliter, 250.0,
                status = ShoppingStatus.AtHome, inPlan = false, note = "Keep this reminder"),
        ))
        val text = shoppingShareText(document, "en", labels())
        assertTrue(text.contains("100 Gram + unspecified addition"))
        assertTrue(text.contains("[~] Milk"))
        assertTrue(text.contains("not in current plan"))
        assertTrue(text.contains("Keep this reminder"))
    }
}
