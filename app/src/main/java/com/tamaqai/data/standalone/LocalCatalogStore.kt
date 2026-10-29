package com.tamaqai.data.standalone

import android.content.Context
import com.tamaqai.data.contracts.CatalogStore
import com.tamaqai.data.local.dao.CatalogDao
import com.tamaqai.data.local.entity.CatalogCacheEntity
import com.tamaqai.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.text.Normalizer
import java.time.Clock
import java.util.Locale

/** Redistributable OFF snapshots packaged in the standalone APK; never downloaded at runtime. */
class LocalCatalogStore(context: Context, private val dao: CatalogDao, private val owner: String,
    private val clock: Clock = Clock.systemDefaultZone()) : CatalogStore {
    override val isBundled = true
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val bundled by lazy {
        val root = json.parseToJsonElement(context.assets.open("standalone/catalog.json").bufferedReader().use { it.readText() })
        root.jsonArray.map { json.decodeFromJsonElement(CatalogEntry.serializer(), it) }.also {
            require(it.isNotEmpty() && it.map(CatalogEntry::id).distinct().size == it.size)
        }
    }
    private fun normalized(value: String) = Regex("[\\p{L}\\p{N}]+")
        .findAll(Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace('ё', 'е'))
        .joinToString(" ") { it.value }
    override suspend fun recent(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        dao.recent(owner).map { json.decodeFromString<CatalogEntry>(it.entryJson) }
    }
    override suspend fun search(text: String, kind: String, city: String?, chain: String?, barcode: String?, branchScope: String?): CatalogResults =
        withContext(Dispatchers.IO) {
            require(kind in listOf("product", "dish"))
            val terms = normalized(text).split(' ').filter(String::isNotBlank)
            CatalogResults(bundled.filter { entry ->
                val words = normalized((listOfNotNull(entry.name, entry.brand, entry.variant, entry.barcode) + entry.aliases).joinToString(" "))
                entry.kind == kind && (city == null || entry.city == city) && (chain == null || entry.chain == chain) &&
                    (branchScope == null || entry.branchScope == branchScope) && (barcode == null || entry.barcode == barcode) && terms.all(words::contains)
            }, offline = false)
        }
    override suspend fun cities(): List<String> = bundled.mapNotNull { it.city }.distinct().sorted()
    override suspend fun restaurants(city: String): List<CatalogRestaurant> = bundled.filter { it.city == city }
        .mapNotNull { entry -> entry.chain?.let { CatalogRestaurant(it, entry.city, entry.branchScope) } }.distinct()
    override suspend fun get(idOrVersion: String, rememberSelection: Boolean): CatalogEntry {
        val value = bundled.firstOrNull { it.id == idOrVersion || it.versionId == idOrVersion }
            ?: throw NoSuchElementException("catalog_not_found")
        if (rememberSelection) remember(value)
        return value
    }
    override suspend fun entry(versionId: String) = get(versionId)
    override suspend fun match(names: List<String>, barcode: String?, city: String?, chain: String?): List<CatalogEntry> =
        if (barcode != null) search("", "product", city, chain, barcode).entries
        else names.flatMap { search(it, "product", city, chain).entries }.distinctBy { it.versionId }
    override suspend fun matchComponents(names: List<String>) = names.flatMapIndexed { index, name ->
        search(name, "product").entries.map { index to it }
    }
    override suspend fun remember(entry: CatalogEntry) = withContext(Dispatchers.IO) {
        require(bundled.any { it == entry })
        dao.remember(CatalogCacheEntity(owner, entry.versionId, json.encodeToString(entry), clock.millis()))
    }
    override suspend fun isFavorite(version: String) = withContext(Dispatchers.IO) { dao.get(owner, version)?.favorite == true }
    override suspend fun favorite(entry: CatalogEntry, favorite: Boolean) {
        remember(entry)
        withContext(Dispatchers.IO) { dao.favorite(owner, entry.versionId, favorite) }
    }
    override suspend fun calculate(entry: CatalogEntry, quantity: Double, unit: String, addons: List<CatalogAddon>): CatalogCalculation {
        val original = get(entry.versionId)
        require(original == entry)
        val extras = addons.map { Triple(get(it.versionId), it.quantity, it.unit) }
        return CachedCatalogCalculator.calculate(original, quantity, unit, extras).also { remember(entry) }
    }
    internal suspend fun seed() { bundled.take(5).forEach { if (dao.get(owner, it.versionId) == null) remember(it) } }
    internal suspend fun clear() { dao.clear(owner) }
}
