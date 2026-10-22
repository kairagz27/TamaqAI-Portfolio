package com.tamaqai.ui.screens

import com.tamaqai.domain.model.Nutrition

sealed interface ScannerState {
    data object Idle : ScannerState
    data object Loading : ScannerState
    data class Error(val message: String) : ScannerState
}
