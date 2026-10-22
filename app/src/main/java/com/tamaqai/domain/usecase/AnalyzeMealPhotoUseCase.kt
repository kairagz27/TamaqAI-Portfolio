package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.AnalysisResult
import com.tamaqai.domain.repository.FoodAnalysisRepository

class AnalyzeMealPhotoUseCase(
    private val foodAnalysisRepository: FoodAnalysisRepository,
) {
    suspend operator fun invoke(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult {
        require(jpegBytes.isNotEmpty()) { "The photo is empty." }
        require(jpegBytes.size <= MAX_IMAGE_BYTES) { "The photo is too large to analyze." }
        return foodAnalysisRepository.analyzePhoto(jpegBytes, analysisId, invite).also { it.validate(analysisId) }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}
