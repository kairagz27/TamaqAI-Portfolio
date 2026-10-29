package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.*

/** Original project fixtures under MIT. Quantities/nutrition are illustrative, not verified label data. */
object DemoRecipes {
    private fun names(en: String, ru: String, kk: String) = mapOf("en" to en, "ru" to ru, "kk" to kk)
    private fun food(
        id: String, en: String, ru: String, kk: String,
        amount: Double?, unit: RecipeUnit, category: ShoppingCategory,
        preparation: FoodPreparation = FoodPreparation.Raw,
        calories: Double? = null, protein: Double? = null, fat: Double? = null, carbs: Double? = null,
    ) = RecipeIngredient(
        id = "$id-${unit.name}-${preparation.name}", productId = id,
        name = en, localizedNames = names(en, ru, kk), quantity = amount, unit = unit,
        category = category, preparation = preparation,
        nutrients = RecipeNutrients(calories, protein, fat, carbs),
    )

    val all: List<Recipe> = listOf(
        Recipe("demo-oats-milk-banana", "Oats with milk and banana", 2.0,
            listOf(
                food("oats", "Oats", "Овсяные хлопья", "Сұлы үлпектері", 60.0, RecipeUnit.Gram, ShoppingCategory.Grains,
                    calories = 222.0, protein = 7.8, fat = 4.2, carbs = 40.0),
                food("milk", "Milk", "Молоко", "Сүт", 250.0, RecipeUnit.Milliliter, ShoppingCategory.Dairy, FoodPreparation.Ready,
                    130.0, 7.5, 6.2, 12.0),
                food("banana", "Banana", "Банан", "Банан", 1.0, RecipeUnit.Piece, ShoppingCategory.Fruit,
                    calories = 100.0, protein = 1.0, fat = .3, carbs = 23.0),
            ), names("Oats with milk and banana", "Овсянка с молоком и бананом", "Сүт пен банан қосылған сұлы")),
        Recipe("demo-chicken-rice", "Chicken, rice and vegetables", 2.0,
            listOf(
                food("chicken", "Chicken", "Курица", "Тауық еті", 300.0, RecipeUnit.Gram, ShoppingCategory.Protein,
                    calories = 330.0, protein = 66.0, fat = 6.0, carbs = 0.0),
                food("rice", "Rice", "Рис", "Күріш", 120.0, RecipeUnit.Gram, ShoppingCategory.Grains,
                    calories = 432.0, protein = 8.0, fat = 1.0, carbs = 94.0),
                food("carrot", "Carrot", "Морковь", "Сәбіз", 200.0, RecipeUnit.Gram, ShoppingCategory.Vegetables,
                    calories = 64.0, protein = 3.0, fat = .5, carbs = 12.0),
            ), names("Chicken, rice and vegetables", "Курица с рисом и овощами", "Күріш пен көкөніс қосылған тауық")),
        Recipe("demo-eggs-vegetables", "Egg and vegetable plate", 2.0,
            listOf(
                food("eggs", "Eggs", "Яйца", "Жұмыртқа", 2.0, RecipeUnit.Piece, ShoppingCategory.Protein,
                    calories = 140.0, protein = 12.0, fat = 10.0, carbs = 1.0),
                food("tomato", "Tomato", "Помидор", "Қызанақ", 150.0, RecipeUnit.Gram, ShoppingCategory.Vegetables,
                    calories = 27.0, protein = 1.3, fat = .3, carbs = 5.9),
                food("cucumber", "Cucumber", "Огурец", "Қияр", 150.0, RecipeUnit.Gram, ShoppingCategory.Vegetables,
                    calories = 23.0, protein = 1.0, fat = .2, carbs = 5.0),
                food("vegetable-oil", "Vegetable oil", "Растительное масло", "Өсімдік майы", 10.0, RecipeUnit.Gram, ShoppingCategory.Other, FoodPreparation.Ready,
                    90.0, 0.0, 10.0, 0.0),
            ), names("Egg and vegetable plate", "Яйца и овощная тарелка", "Жұмыртқа мен көкөніс")),
        Recipe("demo-beans-rice", "Bean and cooked rice bowl", 2.0,
            listOf(
                food("rice", "Cooked rice", "Приготовленный рис", "Піскен күріш", 200.0, RecipeUnit.Gram, ShoppingCategory.Grains, FoodPreparation.Cooked,
                    260.0, 5.0, .6, 56.0),
                food("kidney-beans", "Cooked kidney beans", "Приготовленная фасоль", "Піскен үрме бұршақ", 250.0, RecipeUnit.Gram, ShoppingCategory.Protein, FoodPreparation.Cooked,
                    275.0, 18.0, 1.0, 45.0),
                food("onion", "Onion", "Лук", "Пияз", 100.0, RecipeUnit.Gram, ShoppingCategory.Vegetables,
                    calories = 40.0, protein = 1.0, fat = .1, carbs = 9.0),
            ), names("Bean and cooked rice bowl", "Фасоль с приготовленным рисом", "Піскен күріш пен үрме бұршақ")),
        Recipe("demo-yogurt-fruit", "Yogurt with fruit and oats", 2.0,
            listOf(
                food("plain-yogurt", "Plain yogurt", "Йогурт без добавок", "Қоспасыз йогурт", 300.0, RecipeUnit.Gram, ShoppingCategory.Dairy, FoodPreparation.Ready,
                    180.0, 15.0, 8.0, 12.0),
                food("apple", "Apple", "Яблоко", "Алма", 2.0, RecipeUnit.Piece, ShoppingCategory.Fruit,
                    calories = 160.0, protein = .8, fat = .4, carbs = 40.0),
                food("oats", "Oats", "Овсяные хлопья", "Сұлы үлпектері", 40.0, RecipeUnit.Gram, ShoppingCategory.Grains,
                    calories = 148.0, protein = 5.2, fat = 2.8, carbs = 26.7),
            ), names("Yogurt with fruit and oats", "Йогурт с фруктами и овсянкой", "Жеміс пен сұлы қосылған йогурт")),
        Recipe("demo-vegetable-soup", "Vegetable soup (incomplete example)", 4.0,
            listOf(
                food("potato", "Potato", "Картофель", "Картоп", 400.0, RecipeUnit.Gram, ShoppingCategory.Vegetables,
                    calories = 300.0, protein = 8.0, fat = .4, carbs = 66.0),
                food("carrot", "Carrot", "Морковь", "Сәбіз", 200.0, RecipeUnit.Gram, ShoppingCategory.Vegetables,
                    calories = 64.0, protein = 3.0, fat = .5, carbs = 12.0),
                food("water", "Water", "Вода", "Су", 1.0, RecipeUnit.Liter, ShoppingCategory.Drinks, FoodPreparation.Ready,
                    0.0, 0.0, 0.0, 0.0),
                food("salt", "Salt (quantity unspecified)", "Соль (количество не указано)", "Тұз (мөлшері көрсетілмеген)", null, RecipeUnit.Gram, ShoppingCategory.Spices),
            ), names("Vegetable soup (incomplete example)", "Овощной суп (неполный пример)", "Көкөніс сорпасы (толық емес мысал)")),
    ).also { recipes ->
        require(recipes.map { it.id }.distinct().size == recipes.size)
        recipes.forEach(Recipe::validate)
    }
}
