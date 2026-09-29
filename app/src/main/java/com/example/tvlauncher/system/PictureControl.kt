package com.example.tvlauncher.system

import android.content.Context
import android.provider.Settings
import android.util.Log

private const val TAG = "PictureControl"

/**
 * Reads and changes the TV's picture settings through TCL's own picture-quality library
 * (`com.tcl.tv.display`, declared as an optional `<uses-library>` in the manifest). These are the
 * same calls TCL's Settings app makes (`com.tcl.settings.middleware.picture.PictureManager2`):
 * window 0, the user's "apply to current source / all" choice, and exec + save so a change sticks.
 *
 * Everything goes through reflection so the launcher still builds and runs on a TV without the
 * library; [available] is then false and callers fall back to TCL's own picture panel.
 */
class PictureControl(private val context: Context) {

    enum class Setting(val label: String) {
        BACKLIGHT("Backlight"), BRIGHTNESS("Brightness"), CONTRAST("Contrast"),
        COLOUR("Colour"), SHARPNESS("Sharpness")
    }

    /** SDR picture presets by TCL's internal index (PictureManager2.getPictureMode). HDR/Dolby content uses other indices. */
    val presets: List<Pair<Int, String>> = listOf(
        0 to "Standard", 1 to "Vivid", 3 to "Movie", 4 to "Sport", 5 to "Game", 2 to "Mild"
    )

    /** EnColorTmpType ordinals that are simple named choices. */
    val temperatures: List<Pair<Int, String>> = listOf(0 to "Cool", 1 to "Standard", 2 to "Warm")

    private var pq: Any? = null
    private var backlight: Any? = null
    private var applyEnum: Class<*>? = null
    private var actEnum: Class<*>? = null
    private var colorTmpEnum: Class<*>? = null
    private var fieldEnum: Class<*>? = null

    val available: Boolean

    init {
        available = try {
            pq = Class.forName("com.tcl.tv.pq.TvPqManager").getMethod("getInstance").invoke(null)
            backlight = Class.forName("com.tcl.tv.pq.TvBacklightManager").getMethod("getInstance").invoke(null)
            applyEnum = Class.forName("com.tcl.tv.pq.EnApplyType")
            actEnum = Class.forName("com.tcl.tv.pq.EnTclActType")
            colorTmpEnum = Class.forName("com.tcl.tv.pq.EnColorTmpType")
            fieldEnum = Class.forName("com.tcl.tv.pq.EnPicModeFieldType")
            // One real read proves the service answers us, not just that the classes exist.
            get(Setting.BACKLIGHT) >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "TCL picture library unavailable: $t")
            false
        }
        Log.i(TAG, "available=$available")
    }

    /** 0..100, or -1 if it could not be read. */
    fun get(setting: Setting): Int = try {
        when (setting) {
            Setting.BACKLIGHT -> call(backlight, "getBacklight") as Int
            Setting.BRIGHTNESS -> call(pq, "getBrightness", WINDOW) as Int
            Setting.CONTRAST -> call(pq, "getContrast", WINDOW) as Int
            Setting.COLOUR -> call(pq, "getSaturation", WINDOW) as Int
            Setting.SHARPNESS -> call(pq, "getSharpness", WINDOW) as Int
        }
    } catch (t: Throwable) {
        Log.w(TAG, "get $setting failed: $t"); -1
    }

    fun set(setting: Setting, value: Int): Boolean {
        val v = value.coerceIn(0, 100)
        return try {
            val ok = when (setting) {
                Setting.BACKLIGHT -> call(backlight, "setBacklight", applyType(), v, execSave())
                Setting.BRIGHTNESS -> call(pq, "setBrightness", WINDOW, applyType(), v, execSave())
                Setting.CONTRAST -> call(pq, "setContrast", WINDOW, applyType(), v, execSave())
                Setting.COLOUR -> call(pq, "setSaturation", WINDOW, applyType(), v, execSave())
                Setting.SHARPNESS -> call(pq, "setSharpness", WINDOW, applyType(), v, execSave())
            }
            ok as? Boolean ?: true
        } catch (t: Throwable) {
            Log.w(TAG, "set $setting=$v failed: $t"); false
        }
    }

    /** TCL's raw picture-mode index, or -1. */
    fun getPreset(): Int = try { call(pq, "getPictureMode") as Int } catch (t: Throwable) { -1 }

    fun setPreset(index: Int): Boolean = try {
        val field = enumConst(fieldEnum!!, "EN_PICTURE_MODE_FIELD_NORMAL")
        // TCL only honours "current" or "all picture modes" here, never the max sentinel.
        call(pq, "setPictureMode", WINDOW, applyType(), index, field, execSave()) as? Boolean ?: true
    } catch (t: Throwable) {
        Log.w(TAG, "setPreset $index failed: $t"); false
    }

    fun getTemperature(): Int = try { call(pq, "getColorTemperature", WINDOW) as Int } catch (t: Throwable) { -1 }

    fun setTemperature(ordinal: Int): Boolean = try {
        val value = colorTmpEnum!!.enumConstants!![ordinal]
        call(pq, "setColorTemperature", WINDOW, applyType(), value, execSave()) as? Boolean ?: true
    } catch (t: Throwable) {
        Log.w(TAG, "setTemperature $ordinal failed: $t"); false
    }

    /** Mirrors TCL Settings' "Apply All Picture Mode": 0 = current source only. */
    private fun applyType(): Any {
        val all = Settings.Secure.getInt(context.contentResolver, "tcl_app_settings_picture_apply_mode", 0) != 0
        return enumConst(applyEnum!!, if (all) "EN_APPLY_ALL_PICMODE" else "EN_APPLY_CURRENT")
    }

    private fun execSave(): Any = enumConst(actEnum!!, "EN_TCL_ACT_EXEC_SAVE")

    private fun enumConst(type: Class<*>, name: String): Any = type.enumConstants!!.first { (it as Enum<*>).name == name }

    /** Calls the single public method called [name] whose parameter count matches (TCL's overloads differ by arity). */
    private fun call(target: Any?, name: String, vararg args: Any): Any? {
        val t = target ?: throw IllegalStateException("no target for $name")
        val method = t.javaClass.methods.first { m ->
            m.name == name && m.parameterTypes.size == args.size &&
                m.parameterTypes.zip(args).all { (p, a) -> p.isPrimitive || p.isInstance(a) }
        }
        return method.invoke(t, *args)
    }

    private companion object {
        const val WINDOW = 0
    }
}
