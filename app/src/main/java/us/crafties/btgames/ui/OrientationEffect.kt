package us.crafties.btgames.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Sets the activity's orientation to [orientation] whenever it changes.
 *
 * It deliberately does NOT restore the previous value when it leaves composition. It is called once
 * at the root with the orientation the current screen wants (portrait for menus, sensor-landscape
 * for a landscape game), so a restore step would only briefly re-apply the OLD orientation between
 * two screens and cause a visible rotation flicker.
 */
@Composable
fun OrientationEffect(orientation: Int) {
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(activity, orientation) {
        if (activity != null && activity.requestedOrientation != orientation) {
            activity.requestedOrientation = orientation
        }
    }
}

const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
const val PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

/**
 * Keeps the status and navigation bar icons readable. Edge-to-edge picks light or dark icons from
 * the PHONE's theme by default, which is wrong here: the app has its own dark mode, so a phone in
 * dark mode showing our light paper would get white icons on a near-white page.
 */
@Composable
fun SystemBarsEffect(lightIcons: Boolean) {
    val activity = LocalContext.current.findActivity() as? ComponentActivity
    LaunchedEffect(activity, lightIcons) {
        val style = if (lightIcons) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
