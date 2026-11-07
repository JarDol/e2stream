package pl.jardol.e2stream

import android.view.KeyEvent

/** Akcje, które można przypisać klawiszom pilota. */
enum class Act(val label: String) {
    NONE("— brak —"),
    CHANNEL_LIST("Lista kanałów"),
    EPG_EVENT("EPG wydarzenia (opis bieżącej audycji)"),
    EPG_CHANNEL("EPG kanału"),
    MULTI_EPG("Multi-EPG bukietu"),
    EPG_SEARCH("Szukaj w EPG"),
    AUDIO("Ścieżka dźwięku"),
    STATS("Statystyki"),
    MENU("Menu"),
    SETTINGS("Ustawienia"),
    RECALL("Poprzedni kanał"),
    CH_UP("Kanał +"),
    CH_DOWN("Kanał −"),
}

/** Mapowanie klawiszy w trybie oglądania (bez otwartej listy kanałów). */
object KeyMap {
    /** Klawisze, których nie można przemapować. */
    val fixed = setOf(
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
    ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) + (KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9)

    val defaults: Map<Int, Act> = linkedMapOf(
        KeyEvent.KEYCODE_DPAD_UP to Act.CH_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to Act.CH_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to Act.EPG_CHANNEL,
        KeyEvent.KEYCODE_DPAD_RIGHT to Act.MULTI_EPG,
        KeyEvent.KEYCODE_CHANNEL_UP to Act.CH_UP,
        KeyEvent.KEYCODE_CHANNEL_DOWN to Act.CH_DOWN,
        KeyEvent.KEYCODE_INFO to Act.EPG_EVENT,
        KeyEvent.KEYCODE_GUIDE to Act.MULTI_EPG,
        KeyEvent.KEYCODE_TV_DATA_SERVICE to Act.MULTI_EPG,
        KeyEvent.KEYCODE_SEARCH to Act.EPG_SEARCH,
        KeyEvent.KEYCODE_MENU to Act.MENU,
        KeyEvent.KEYCODE_SETTINGS to Act.SETTINGS,
        KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK to Act.AUDIO,
        KeyEvent.KEYCODE_LAST_CHANNEL to Act.RECALL,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to Act.RECALL,
    )

    fun load(prefs: Prefs): MutableMap<Int, Act> {
        val m = LinkedHashMap(defaults)
        for (part in prefs.keyMap.split(",")) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            val code = kv[0].trim().toIntOrNull() ?: continue
            val act = try { Act.valueOf(kv[1].trim()) } catch (_: Exception) { continue }
            m[code] = act
        }
        return m
    }

    fun save(prefs: Prefs, m: Map<Int, Act>) {
        prefs.keyMap = m.entries.joinToString(",") { "${it.key}=${it.value.name}" }
    }

    fun keyName(code: Int): String = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
}
