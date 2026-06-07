package iam699030.gmail.movitop.map

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import org.mapsforge.map.android.rendertheme.AssetsRenderTheme
import org.mapsforge.map.rendertheme.InternalRenderTheme
import org.mapsforge.map.rendertheme.XmlRenderTheme

/** Loads the modern Movitop Mapsforge render theme (day/night). */
object MapThemeHelper {

    private const val TAG = "MapThemeHelper"
    private const val THEME_DAY = "custom_theme.xml"
    private const val THEME_NIGHT = "custom_theme_night.xml"

    fun load(context: Context): XmlRenderTheme {
        val nightMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val file = if (nightMode) THEME_NIGHT else THEME_DAY
        return try {
            AssetsRenderTheme(context.assets, "", file)
        } catch (e: Exception) {
            Log.w(TAG, "Custom theme $file failed, falling back to DEFAULT", e)
            InternalRenderTheme.DEFAULT
        }
    }
}
