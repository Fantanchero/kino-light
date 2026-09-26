package com.arkiv.player.ui.titleinfo

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.gateway.GatewayResult

/** What the person reads when a title cannot be opened. */
const val TITLE_OPEN_ERROR = "No se pudo abrir este título."

/**
 * What tapping a plugin card does: open its information page. A result that has no route (blank
 * id, a ref that is not the plugin's own) says so in a toast: a tap is never silent.
 */
@Composable
fun rememberTitleOpener(onOpenRoute: (String) -> Unit): (GatewayResult) -> Unit {
    val context = LocalContext.current
    val currentOpen by rememberUpdatedState(onOpenRoute)
    return remember(context) {
        { result ->
            val route = titleRoute(result)
            if (route != null) currentOpen(route)
            else Toast.makeText(context, TITLE_OPEN_ERROR, Toast.LENGTH_SHORT).show()
        }
    }
}
