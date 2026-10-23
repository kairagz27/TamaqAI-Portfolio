package com.tamaqai
import com.tamaqai.data.AppContainer
class TamaqStandaloneApplication:TamaqApplication() {
 override fun createContainer()=AppContainer(this,offline=true)
}
