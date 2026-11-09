package com.tamaqai.domain.usecase

import com.tamaqai.domain.model.AnalysisResult
import com.tamaqai.domain.model.Meal

/** Save only an unambiguous food estimate; keep clarification and editing drafts reviewable. */
object PhotoAnalysisSavePolicy {
    fun shouldSave(response: AnalysisResult, draft: Meal): Boolean =
        response.result.status == "food" && response.result.components.isNotEmpty() &&
            draft.originalUid == null && !draft.details.userEdited
}
