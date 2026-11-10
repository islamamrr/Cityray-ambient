package dev.carapps.car

import android.content.Context
import org.json.JSONObject

/**
 * Every id the ECARX AdaptAPI (version 1.1.18.12) names: about 150 sensors, 850 car
 * functions, 50 car-info items, 6 sensor groups and 340 key codes, with the names of
 * their values where known. Loaded from `assets/ecarx_catalog.json`.
 *
 * The catalog says what an id is CALLED, not whether this car sends it. On the Cityray most
 * ids are probably unsupported. Use [EcarxCar.scan] to find out which ones answer, and keep
 * the curated, tested ids in [CarSensor] / [CarFunction].
 *
 * Source: the AdaptAPI interface constants that apps on this head unit compile against
 * (ISensor, ISensorGroup, ICarInfo, IVehicle, KeyCode and the module interfaces such as
 * IHvac or IBcm). Only names and numbers are kept. See tools/catalog/README.md.
 */
class EcarxCatalog private constructor(
    val entries: List<Entry>,
    private val keys: Map<Int, String>,
) {

    enum class Kind { SENSOR, FUNCTION, INFO, CONFIG, GROUP }

    data class Entry(
        val kind: Kind,
        val id: Int,
        /** ECARX constant name, for example SENSOR_TYPE_GEAR or HVAC_FUNC_POWER. */
        val name: String,
        /** ECARX interface that declares it, for example ISensor or IHvac. */
        val iface: String,
        /** Named values, for example IGNITION_STATE_ON -> 0x200105. Often empty. */
        val values: Map<String, Int>,
        /** Other names the same id has in other ECARX versions. */
        val aka: List<String>,
    ) {
        /** Car module, for functions: HVAC, BCM, SEAT, ... (from the id's top byte). */
        val module: String get() = moduleOf(id)

        fun valueName(value: Int): String? = values.entries.firstOrNull { it.value == value }?.key
    }

    private val byKey = entries.associateBy { it.kind to it.id }

    val sensors: List<Entry> get() = entries.filter { it.kind == Kind.SENSOR }
    val functions: List<Entry> get() = entries.filter { it.kind == Kind.FUNCTION }
    val infos: List<Entry> get() = entries.filter { it.kind == Kind.INFO }
    val groups: List<Entry> get() = entries.filter { it.kind == Kind.GROUP }

    fun find(kind: Kind, id: Int): Entry? = byKey[kind to id]

    fun sensor(id: Int): Entry? = find(Kind.SENSOR, id)
    fun function(id: Int): Entry? = find(Kind.FUNCTION, id)

    /** ECARX name of a steering-wheel / panel key code, for example KEYCODE_R_MENU. */
    fun keyName(code: Int): String? = keys[code]

    /** A readable name for a sensor or function value, such as "IGNITION_STATE_ON", or null. */
    fun valueName(kind: Kind, id: Int, value: Int): String? = find(kind, id)?.valueName(value)

    companion object {
        const val ASSET = "ecarx_catalog.json"

        @Volatile private var cached: EcarxCatalog? = null

        /** Loads once and caches. Returns an empty catalog if the asset can't be read. */
        fun get(context: Context): EcarxCatalog {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val c = runCatching {
                    context.applicationContext.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }
                }.getOrElse { EcarxCatalog(emptyList(), emptyMap()) }
                cached = c
                return c
            }
        }

        internal fun parse(json: String): EcarxCatalog {
            val root = JSONObject(json)
            val arr = root.getJSONArray("entries")
            val list = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = runCatching { Kind.valueOf(o.getString("kind").uppercase()) }.getOrNull() ?: continue
                val values = LinkedHashMap<String, Int>()
                o.optJSONObject("values")?.let { v -> v.keys().forEach { k -> values[k] = v.getInt(k) } }
                val aka = ArrayList<String>()
                o.optJSONArray("aka")?.let { a -> for (j in 0 until a.length()) aka.add(a.getString(j)) }
                list.add(Entry(kind, o.getInt("id"), o.getString("name"), o.getString("iface"), values, aka))
            }
            val keys = HashMap<Int, String>()
            root.optJSONArray("keys")?.let { k ->
                for (i in 0 until k.length()) {
                    val o = k.getJSONObject(i)
                    keys.putIfAbsent(o.getInt("code"), o.getString("name"))
                }
            }
            return EcarxCatalog(list, keys)
        }

        /** ECARX car modules, keyed by the top byte of a function id (ICarFunction.CAR_MODULE_*). */
        private val MODULES = mapOf(
            0x10 to "HVAC", 0x20 to "SETTING", 0x21 to "BCM", 0x22 to "DRIVE_MODE", 0x23 to "PAS",
            0x24 to "HYBRID", 0x25 to "UNIT", 0x26 to "WPC", 0x27 to "HUD", 0x28 to "ADAS",
            0x29 to "DAYMODE", 0x2A to "AMBIENCE_LIGHT", 0x2B to "LAMP", 0x2C to "SAFETY",
            0x2D to "SEAT", 0x2E to "AUDIO", 0x2F to "SCENE", 0x81 to "VOICE", 0x80 to "VENDOR",
        )

        fun moduleOf(id: Int): String = MODULES[id ushr 24] ?: "COMMON"
    }
}
