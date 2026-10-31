package dev.altayeb.shoebill.subs

/** Key storage. The app backs it with SharedPreferences "subkeys"; kept interface-only so JVM tests compile this file. */
interface KeyStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
}

/** Per-provider API keys. A provider without a key is never searched. */
class SubKeys(private val store: KeyStore) {
    fun key(providerId: String): String? = store.get(providerId)?.trim()?.takeIf { it.isNotEmpty() }

    fun save(providerId: String, value: String) = store.put(providerId, value.trim())

    /** Keyed providers, minus [skip] (providers hidden for this session). Never logs keys. */
    fun providers(skip: Set<String> = emptySet()): List<SubtitleProvider> = buildList {
        key("opensubtitles")?.let { add(OpenSubtitlesProvider(it)) }
        key("subdl")?.let { add(SubDlProvider(it)) }
        key("jimaku")?.let { add(JimakuProvider(it)) }
    }.filter { it.id !in skip }

    companion object {
        val LABELS = linkedMapOf("opensubtitles" to "OpenSubtitles", "subdl" to "SubDL", "jimaku" to "Jimaku")
    }
}
