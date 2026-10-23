package com.tamaqai.data.remote

import android.icu.lang.UCharacter
import com.tamaqai.data.local.dao.CatalogDao
import com.tamaqai.data.local.entity.CatalogCacheEntity
import com.tamaqai.domain.model.*
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** All cache reads/writes and late responses are scoped to the captured session owner. */
class CatalogRepository(private val client: SessionClient, private val dao: CatalogDao, private val owner: String) : com.tamaqai.data.contracts.CatalogStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun checkOwner() = client.requireOwner(owner)
    private suspend fun call(path: String, method: String = "GET", body: JSONObject = JSONObject()): JSONObject {
        checkOwner()
        val response = if (owner != "guest") client.authorized(path, owner, method, body)
        // Shared catalogue browsing does not require a pilot invitation or a valid AI guest token.
        else client.raw(path, method, body)
        checkOwner()
        return response
    }
    private fun entry(value: JSONObject): CatalogEntry = json.decodeFromString(value.toString())
    private fun entries(array: JSONArray): List<CatalogEntry> = (0 until array.length()).map { entry(array.getJSONObject(it)) }
    private fun query(value: String) = URLEncoder.encode(value, "UTF-8")
    private val words = Regex("[\\p{L}\\p{N}]+")
    /** Mirrors server catalogue normalization: NFKC, Unicode case folding, ё/е, word tokens. */
    private fun normalized(value: String): String = words.findAll(
        UCharacter.foldCase(Normalizer.normalize(value, Normalizer.Form.NFKC), true).replace('ё', 'е')
    ).joinToString(" ") { it.value }
    /** A retained owner can use its local snapshots after offline refresh expires the session. */
    private fun canUseCache(error: Exception): Boolean = error is IOException || error is ApiFailure &&
        (error.status in 502..504 || error.status == 401 && error.code == "session_expired")

    override suspend fun recent(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        checkOwner()
        dao.recent(owner).mapNotNull { runCatching { json.decodeFromString<CatalogEntry>(it.entryJson) }.getOrNull() }.also { checkOwner() }
    }

    override suspend fun search(text: String, kind: String, city: String?, chain: String?, barcode: String?, branchScope: String?): CatalogResults {
        require(kind in listOf("product", "dish"))
        return try {
            val path = "/v1/catalog/search?q=${query(text.trim())}&kind=$kind&limit=30" +
                (city?.let { "&city=${query(it)}" } ?: "") + (chain?.let { "&chain=${query(it)}" } ?: "") +
                (barcode?.let { "&barcode=${query(it)}" } ?: "") + (branchScope?.let { "&branch_scope=${query(it)}" } ?: "")
            CatalogResults(entries(call(path).getJSONArray("items")))
        } catch (error: Exception) {
            if (error is CancellationException || !canUseCache(error)) throw error
            val terms = normalized(text).split(' ').filter(String::isNotEmpty)
            CatalogResults(recent().filter { candidate ->
                val searchText = normalized((listOfNotNull(candidate.name, candidate.brand, candidate.barcode,
                    candidate.chain, candidate.city, candidate.variant) + candidate.aliases).joinToString(" "))
                candidate.kind == kind &&
                    (city == null || candidate.city == city) && (chain == null || candidate.chain == chain) &&
                    (branchScope == null || candidate.branchScope == branchScope) &&
                    (barcode == null || candidate.barcode == barcode) && terms.all { searchText.contains(it) }
            }, true)
        }
    }

    override suspend fun cities(): List<String> = try {
        val array = call("/v1/catalog/cities").getJSONArray("items")
        (0 until array.length()).map(array::getString)
    } catch (error: Exception) {
        if (error is CancellationException || !canUseCache(error)) throw error
        recent().filter { it.kind == "dish" }.mapNotNull { it.city }.distinct().sorted()
    }

    override suspend fun restaurants(city: String): List<CatalogRestaurant> = try {
        val array = call("/v1/catalog/restaurants?city=${query(city)}").getJSONArray("items")
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            CatalogRestaurant(it.getString("chain"), it.optString("city").takeIf(String::isNotBlank), it.optString("branch_scope").takeIf(String::isNotBlank))
        } }
    } catch (error: Exception) {
        if (error is CancellationException || !canUseCache(error)) throw error
        recent().filter { it.kind == "dish" && it.city == city }.mapNotNull { candidate -> candidate.chain?.let {
            CatalogRestaurant(it, candidate.city, candidate.branchScope) } }.distinct()
    }

    override suspend fun get(idOrVersion: String, rememberSelection: Boolean): CatalogEntry {
        val result = try {
            entry(call("/v1/catalog/entries/${query(idOrVersion)}"))
        } catch (error: Exception) {
            if (error is CancellationException || !canUseCache(error)) throw error
            recent().firstOrNull { it.versionId == idOrVersion || it.id == idOrVersion } ?: throw error
        }
        if (rememberSelection) remember(result)
        return result
    }

    override suspend fun entry(versionId: String): CatalogEntry = get(versionId)

    override suspend fun match(names: List<String>, barcode: String?, city: String?, chain: String?): List<CatalogEntry> {
        val body = JSONObject().put("names", JSONArray(names)).put("barcode", barcode ?: JSONObject.NULL)
            .put("city", city ?: JSONObject.NULL).put("chain", chain ?: JSONObject.NULL)
        return entries(call("/v1/catalog/match", "POST", body).getJSONArray("candidates"))
    }

    /** Preserve the recognition component index: selecting rice must never replace the whole dish. */
    override suspend fun matchComponents(names: List<String>): List<Pair<Int,CatalogEntry>> {
        val response=call("/v1/catalog/match","POST",JSONObject().put("names",JSONArray(names)))
        val groups=response.getJSONArray("matches")
        require(groups.length()==names.size)
        return (0 until groups.length()).flatMap {index ->
            val group=groups.getJSONObject(index)
            require(group.getString("name")==names[index])
            entries(group.getJSONArray("candidates")).map {index to it}
        }
    }

    override suspend fun remember(entry: CatalogEntry) = withContext(Dispatchers.IO) {
        checkOwner()
        dao.remember(CatalogCacheEntity(owner, entry.versionId, json.encodeToString(entry), System.currentTimeMillis()))
        checkOwner()
    }

    override suspend fun isFavorite(version: String): Boolean = withContext(Dispatchers.IO) { checkOwner(); dao.get(owner, version)?.favorite == true }
    override suspend fun favorite(entry: CatalogEntry, favorite: Boolean) = withContext(Dispatchers.IO) {
        remember(entry); checkOwner(); dao.favorite(owner, entry.versionId, favorite); checkOwner()
    }

    override suspend fun calculate(entry: CatalogEntry, quantity: Double, unit: String, addons: List<CatalogAddon>): CatalogCalculation {
        val body = JSONObject().put("version_id", entry.versionId).put("quantity", quantity).put("unit", unit)
            .put("addons", JSONArray(addons.map { JSONObject().put("version_id", it.versionId).put("quantity", it.quantity).put("unit", it.unit) }))
        val calculation = try {
            json.decodeFromString<CatalogCalculation>(call("/v1/catalog/calculate", "POST", body).toString())
        } catch (error: Exception) {
            if (error is CancellationException || !canUseCache(error)) throw error
            val cached = recent()
            val base = cached.firstOrNull { it.versionId == entry.versionId } ?: throw error
            val extras = addons.map { addon ->
                val extra = cached.firstOrNull { it.versionId == addon.versionId } ?: throw error
                require(extra.id in base.addonIds) { "catalog_addon_invalid" }
                Triple(extra, addon.quantity, addon.unit)
            }
            CachedCatalogCalculator.calculate(base, quantity, unit, extras)
        }
        require(calculation.nutrients.complete) { "catalog_nutrition_missing" }
        checkOwner()
        remember(entry)
        return calculation
    }
}
