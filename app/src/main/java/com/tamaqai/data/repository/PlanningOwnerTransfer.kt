package com.tamaqai.data.repository

import androidx.room.withTransaction
import com.tamaqai.data.local.AppDatabase
import com.tamaqai.domain.model.PlanningWorkspace
import com.tamaqai.domain.model.ShoppingDocument
import kotlinx.serialization.json.Json

/**
 * Local half of explicit guest linkage. The account service must validate both session
 * proofs before transfer; this helper has no authentication or network responsibilities.
 * Unknown documents fail closed. It only removes pristine, empty target metadata.
 */
internal object PlanningOwnerTransfer {
    private val json = Json { encodeDefaults = true }
    private data class Documents(val workspace: PlanningWorkspace?, val shopping: ShoppingDocument?) {
        val exists get() = workspace != null || shopping != null
    }

    suspend fun validate(database: AppDatabase, fromOwner: String, toOwner: String) = database.withTransaction {
        validateOwners(fromOwner, toOwner)
        val source = read(database, fromOwner)
        if (source.exists) requireEmptyTarget(read(database, toOwner))
    }

    suspend fun transfer(database: AppDatabase, fromOwner: String, toOwner: String) = database.withTransaction {
        validateOwners(fromOwner, toOwner)
        val source = read(database, fromOwner)
        if (!source.exists) return@withTransaction
        val target = read(database, toOwner)
        requireEmptyTarget(target)
        val sql = database.openHelper.writableDatabase
        // Only documents proven pristine and empty above are removed.
        if (target.shopping != null) sql.execSQL("DELETE FROM shopping_documents WHERE owner=?", arrayOf(toOwner))
        if (target.workspace != null) sql.execSQL("DELETE FROM planning_workspaces WHERE owner=?", arrayOf(toOwner))
        sql.execSQL("UPDATE planning_workspaces SET owner=? WHERE owner=?", arrayOf(toOwner, fromOwner))
        sql.execSQL("UPDATE shopping_documents SET owner=? WHERE owner=?", arrayOf(toOwner, fromOwner))
    }

    private fun validateOwners(fromOwner: String, toOwner: String) {
        check(fromOwner == "guest" && toOwner.isNotBlank() && toOwner != "guest") { "planning_link_conflict" }
    }

    private fun read(database: AppDatabase, owner: String): Documents {
        val sql = database.openHelper.writableDatabase
        val workspaces = sql.query("SELECT id,documentJson FROM planning_workspaces WHERE owner=?", arrayOf(owner)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val value = decode { json.decodeFromString<PlanningWorkspace>(cursor.getString(1)).also { it.validate() } }
                    check(id == RoomPlanningRepository.WORKSPACE_ID && value.id == id) { "planning_link_conflict" }
                    add(value)
                }
            }
        }
        val shopping = sql.query("SELECT workspaceId,documentJson FROM shopping_documents WHERE owner=?", arrayOf(owner)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val value = decode { json.decodeFromString<ShoppingDocument>(cursor.getString(1)).also { it.validate() } }
                    check(id == RoomPlanningRepository.WORKSPACE_ID && value.workspaceId == id) { "planning_link_conflict" }
                    add(value)
                }
            }
        }
        check(workspaces.size <= 1 && shopping.size <= 1 && (shopping.isEmpty() || workspaces.isNotEmpty())) { "planning_link_conflict" }
        return Documents(workspaces.singleOrNull(), shopping.singleOrNull())
    }

    private inline fun <T> decode(block: () -> T): T = try { block() }
    catch (_: Exception) { throw IllegalStateException("planning_link_conflict") }

    private fun requireEmptyTarget(target: Documents) {
        target.workspace?.let {
            check(it.revision == 0L && it.entries.isEmpty() && it.importedPlanIds.isEmpty() && it.dayCount == 3) { "planning_link_conflict" }
        }
        target.shopping?.let {
            check(it.sourceRevision == 0L && it.rows.isEmpty()) { "planning_link_conflict" }
        }
    }
}
