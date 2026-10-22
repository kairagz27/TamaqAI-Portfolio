package com.tamaqai.domain.repository

import com.tamaqai.domain.model.AnalysisResult

interface FoodAnalysisRepository {
    suspend fun analyzePhoto(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult
}

class FoodAnalysisConfigurationException : IllegalStateException(
    "ui_error_034",
)
