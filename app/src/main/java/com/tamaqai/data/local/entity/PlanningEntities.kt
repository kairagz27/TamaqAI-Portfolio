package com.tamaqai.data.local.entity

import androidx.room.Entity

/** Documents are keyed by both owner and identity, including the isolated standalone owner. */
@Entity(tableName = "planning_workspaces", primaryKeys = ["owner", "id"])
data class PlanningWorkspaceEntity(val owner: String, val id: String, val documentJson: String)

@Entity(tableName = "shopping_documents", primaryKeys = ["owner", "workspaceId"])
data class ShoppingDocumentEntity(val owner: String, val workspaceId: String, val documentJson: String)
