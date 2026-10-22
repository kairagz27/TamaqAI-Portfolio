package com.tamaqai.domain.model

data class Questionnaire(val step:Int=0,val completed:Boolean=false,val goal:String="diary",val age:Int?=null,
    val height:Double?=null,val weight:Double?=null,val sex:String="unspecified",val activity:String="light",val special:Boolean=false,
    val meals:Int=3,val times:List<String> = listOf("08:00","13:00","19:00"),val preferences:String="",
    val exclusions:List<String> = emptyList(),val allergies:List<String> = emptyList(),val budget:String="",val cooking:Int=30)

object TargetCalculator {
    fun calculate(q:Questionnaire):DailyGoals {
        if(q.goal=="diary") return DailyGoals()
        val age=requireNotNull(q.age);val height=requireNotNull(q.height);val weight=requireNotNull(q.weight)
        require(height.isFinite() && weight.isFinite() && height in 50.0..250.0 && weight in 20.0..500.0)
        require(age in 19..78 && !q.special && weight/(height/100)/(height/100) in 18.5..40.0 && q.sex in listOf("male","female")) {"automatic_calculation_unsuitable"}
        val factor=mapOf("sedentary" to 1.4,"light" to 1.6,"moderate" to 1.8,"active" to 2.0).getValue(q.activity)
        val maintenance=(10*weight+6.25*height-5*age+if(q.sex=="male") 5 else -161)*factor
        val adjustment=minOf(300.0,maintenance*.1)
        val calories=(maintenance+when(q.goal) {"lose"->-adjustment;"gain"->adjustment;"maintain"->0.0;else->error("invalid_goal")}+.5).toInt()
        require(calories in 1500..4500) {"automatic_calculation_unsuitable"}
        return DailyGoals(calories,(calories*.20/4+.5).toInt(),(calories*.30/9+.5).toInt(),(calories*.50/4+.5).toInt())
    }
}
