package com.tamaqai.data.remote

import android.content.Context
import com.tamaqai.BuildConfig
import com.tamaqai.domain.model.AnalysisResult
import com.tamaqai.domain.repository.FoodAnalysisConfigurationException
import com.tamaqai.domain.repository.FoodAnalysisRepository
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class BackendFoodAnalysisRepository(context: Context, private val baseUrl: String,
    private val client: SessionClient = SessionClient(SecureSessions(context,baseUrl),baseUrl),
    private val owner: String = client.sessions.owner) : FoodAnalysisRepository {
    private val json = Json { ignoreUnknownKeys = false }
    override suspend fun analyzePhoto(jpegBytes: ByteArray, analysisId: String, invite: String): AnalysisResult {
        if (baseUrl.isBlank()) throw FoodAnalysisConfigurationException()
        val body = try { client.analyze(owner,jpegBytes,analysisId,invite) }
        catch (e: ApiFailure) { error(messageFor(e.code)) }
        val result=json.decodeFromString<AnalysisResult>(body)
        require(BuildConfig.DEBUG || !result.isDemo) { "ui_error_020" }
        return result
    }
    private fun messageFor(code: String?) = when (code) {
        "backend_not_configured" -> "ui_error_034"
        "backend_https_required" -> "ui_error_016"
        "invite_required", "unauthorized", "email_not_verified" -> "ui_error_022"
        "token_expired", "session_expired" -> "ui_error_023"
        "pilot_stopped" -> "ui_error_024"
        "registration_limit" -> "ui_error_025"
        "daily_quota", "rate_limit", "provider_rate_limit", "global_budget_exhausted" -> "ui_error_026"
        "model_unavailable", "provider_not_configured", "provider_unavailable" -> "ui_error_027"
        "refusal" -> "ui_error_028"
        "image_too_large", "unsupported_image", "invalid_image" -> "ui_error_029"
        "incomplete_response", "invalid_provider_result" -> "ui_error_030"
        "idempotency_conflict" -> "ui_error_031"
        "provider_outcome_unknown", "analysis_outcome_unknown", "usage_unknown" -> "ui_error_032"
        else -> "ui_error_033"
    }
}
