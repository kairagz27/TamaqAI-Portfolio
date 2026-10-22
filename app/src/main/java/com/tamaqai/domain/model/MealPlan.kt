package com.tamaqai.domain.model

import java.time.LocalDate
import java.util.UUID

data class PlanNutrients(val calories:Double,val protein:Double,val fat:Double,val carbs:Double) {
    fun scale(f:Double)=PlanNutrients(calories*f,protein*f,fat*f,carbs*f)
    operator fun plus(v:PlanNutrients)=PlanNutrients(calories+v.calories,protein+v.protein,fat+v.fat,carbs+v.carbs)
    fun validate() {require(listOf(calories,protein,fat,carbs).all {it.isFinite() && it>=0});require(calories<=10000 && maxOf(protein,fat,carbs)<=1000)}
}
data class PlanIngredient(val id:String=UUID.randomUUID().toString(),val name:String="",val quantity:Double=100.0,val unit:String="g",val basis:Double=quantity,val nutrition:PlanNutrients?=null) {
    val total get()=nutrition?.scale(quantity/basis)
    fun validate() {require(name.isNotBlank() && name.length<=120 && quantity.isFinite() && basis.isFinite() && quantity in .001..10000.0 && basis in .001..10000.0 && unit in listOf("g","ml","piece"));nutrition?.validate();total?.validate()}
}
data class PlannedMeal(val id:String=UUID.randomUUID().toString(),val time:String="12:00",val dish:String="",val ingredients:List<PlanIngredient> = emptyList()) {
    val total:PlanNutrients? get()=if(ingredients.isEmpty() || ingredients.any {it.nutrition==null}) null else ingredients.map {it.total!!}.reduce(PlanNutrients::plus)
    fun validate() {require(dish.isNotBlank() && dish.length<=120 && time.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")) && ingredients.size in 1..12 && ingredients.map {it.id}.distinct().size==ingredients.size);ingredients.forEach {it.validate()};total?.validate()}
}
data class MealPlan(val id:String=UUID.randomUUID().toString(),val version:Int=0,val date:LocalDate=LocalDate.now(),val source:String="manual",val meals:List<PlannedMeal> = emptyList(),val dirty:Boolean=true,val demo:Boolean=false) {
    val total:PlanNutrients? get()=if(meals.any {it.total==null}) null else meals.map {it.total!!}.fold(PlanNutrients(0.0,0.0,0.0,0.0),PlanNutrients::plus)
    fun validate() {require(meals.size<=6 && meals.map {it.id}.distinct().size==meals.size);meals.forEach {it.validate()};total?.validate()}
}
