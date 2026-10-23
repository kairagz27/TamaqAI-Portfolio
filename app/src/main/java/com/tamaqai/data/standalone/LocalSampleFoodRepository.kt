package com.tamaqai.data.standalone

import android.content.Context
import com.tamaqai.R
import com.tamaqai.domain.model.AnalysisComponent
import com.tamaqai.domain.model.AnalysisResult
import com.tamaqai.domain.model.FoodResult
import com.tamaqai.domain.repository.FoodAnalysisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

data class DemoSample(val id: String, val assetPath: String, val name: String)

/** A prepared result belongs only to this exact bundled CC0 image, never an arbitrary user photo. */
class LocalSampleFoodRepository(private val context: Context, private val text: (Int) -> String) : FoodAnalysisRepository {
    fun samples() = listOf(DemoSample("salad", SALAD_ASSET, text(R.string.standalone_sample_salad)))
    fun image(sampleId: String): ByteArray {
        val sample = samples().single { it.id == sampleId }
        return context.assets.open(sample.assetPath).use { it.readBytes() }
    }
    private val allowedDigest by lazy { digest(image("salad")) }

    override suspend fun analyzePhoto(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult = withContext(Dispatchers.IO) {
        require(MessageDigest.isEqual(digest(jpegBytes), allowedDigest)) { "standalone_own_photo_unavailable" }
        AnalysisResult(analysisId, "1", "standalone-prepared-salad-v1", "LOCAL-PREPARED-EXAMPLE", true,
            FoodResult("food", text(R.string.standalone_sample_salad), listOf(
                AnalysisComponent(text(R.string.standalone_sample_leaves), 100.0, 15.0, 1.4, .2, 2.5),
                AnalysisComponent(text(R.string.standalone_sample_tomato), 150.0, 27.0, 1.3, .3, 5.9),
                AnalysisComponent(text(R.string.standalone_sample_cheese), 60.0, 150.0, 9.0, 12.0, 1.2),
                AnalysisComponent(text(R.string.standalone_sample_dressing), 15.0, 45.0, 0.0, 3.0, 4.5),
            ), text(R.string.standalone_sample_explanation), null))
    }

    companion object {
        const val SALAD_ASSET = "examples/salad.jpg"
        internal fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
