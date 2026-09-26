package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.magis.MagisRef
import com.arkiv.player.data.plugin.PluginIds
import com.arkiv.player.data.plugin.PluginOutput
import com.arkiv.player.data.plugin.PluginRef
import java.net.URLEncoder

/** How many characters of a synopsis travel in the route. See [clipSynopsis]. */
const val TITLE_DESC_MAX = 600

/**
 * Longest image URL a route carries: the longest one a plugin may send (signed CDN URLs reach it,
 * and playing saves the library row's art from the same result). A longer one cannot come from a
 * plugin and is left out: a cut URL is a broken image.
 */
const val TITLE_URL_MAX = PluginOutput.MAX_IMAGE_URL_CHARS

/** Where the page's item came from, decoded from the route. */
sealed interface TitleOrigin {
    data object Magis : TitleOrigin
    data class Plugin(val extras: PluginTitleExtras) : TitleOrigin
}

/**
 * Cuts a synopsis to [max] characters, ending in an ellipsis when it had to cut.
 *
 * The page's data travels IN the route so it survives process death, and a route is a URL, so it
 * has to stay short. Nothing refetches a longer synopsis (the gateway layer does not return one),
 * so this is the limit of what the page shows; Magis synopses are short.
 */
internal fun clipSynopsis(text: String, max: Int = TITLE_DESC_MAX): String =
    if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

/**
 * Percent-encodes with `%20` for spaces. `URLEncoder` gives `+`, which Android's `Uri.decode`
 * would leave as a literal plus.
 */
private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * The route of a card's info page, or null when the card has no id (a blank id would make
 * `title/` and crash Navigation). Everything the page paints first travels in the route, so it
 * survives the system killing the process while the player is on top.
 */
fun titleRoute(item: CatalogItem): String? = routeOf(item, extraArgs = "")

private fun routeOf(item: CatalogItem, extraArgs: String): String? {
    if (item.id.isBlank()) return null
    return "title/${enc(item.id)}" +
        "?type=${enc(item.type)}" +
        "&title=${enc(item.title)}" +
        "&poster=${enc(item.poster.orEmpty())}" +
        "&backdrop=${enc(item.backdrop.orEmpty())}" +
        "&count=${item.episodeCount}" +
        "&score=${item.score?.toString().orEmpty()}" +
        "&genres=${enc(item.genres.joinToString("|"))}" +
        "&duration=${item.durationS}" +
        "&adult=${if (item.adult) "1" else ""}" +
        "&desc=${enc(clipSynopsis(item.description))}" +
        extraArgs
}

/**
 * A plugin search result or Home card as the catalog item the page opens with, or null when it is
 * not a plugin title, has no item id, or its ref is not that plugin's own. Image URLs over
 * [TITLE_URL_MAX] are dropped, which bounds the route.
 */
fun GatewayResult.toPluginCatalogItem(): CatalogItem? {
    val pluginId = PluginIds.pluginIdOfSource(source) ?: return null
    val itemId = extra["pluginItemId"].orEmpty()
    if (itemId.isBlank() || PluginRef.decode(ref)?.pluginId != pluginId) return null
    fun url(key: String) = extra[key]?.takeIf { it.isNotBlank() && it.length <= TITLE_URL_MAX }
    return CatalogItem(
        id = itemId,
        title = title,
        poster = url("poster"),
        durationS = (extra["runtimeMinutes"]?.toIntOrNull() ?: 0) * 60,
        ref = ref,
        type = if (kind == "series") "series" else "movie",
        genres = extra["genres"].orEmpty().split(", ").filter { it.isNotBlank() },
        score = extra["rating"]?.toDoubleOrNull(),
        backdrop = url("backdrop"),
        description = extra["overview"].orEmpty(),
    )
}

/** The route for a Magis or plugin result, or null when it is neither (or cannot be opened). */
fun titleRoute(result: GatewayResult): String? {
    result.toMagisCatalogItem()?.let { return titleRoute(it) }
    val item = result.toPluginCatalogItem() ?: return null
    val plugin = "&src=plugin" +
        "&ref=${enc(item.ref)}" +
        "&plugin=${enc(result.extra["pluginName"].orEmpty())}" +
        "&color=${enc(result.extra["color"].orEmpty())}" +
        "&year=${enc(result.year)}" +
        "&tmdb=${result.extra["tmdbId"]?.toIntOrNull()?.takeIf { it > 0 } ?: ""}" +
        "&imdb=${enc(result.extra["imdbId"].orEmpty())}"
    return routeOf(item, plugin)
}

/**
 * Rebuilds the [CatalogItem] a title route carried. [arg] returns one argument already URL-decoded,
 * which is what `entry.arguments?.getString(name)` gives; taking a function keeps this free of
 * Android types. Null when the id is blank, or a plugin route has no ref.
 *
 * A Magis ref is rebuilt from id and type because that is exactly what the portal's cards carry
 * (`MagisRef(id, type, 0).encode()`); a plugin's ref travels whole.
 */
fun titleItemFrom(arg: (String) -> String?): CatalogItem? {
    val id = arg("id").orEmpty()
    if (id.isBlank()) return null
    val plugin = arg("src") == "plugin"
    val type = if (plugin) {
        if (arg("type") == "series") "series" else "movie"
    } else {
        arg("type").orEmpty().ifBlank { "movie" }
    }
    val ref = if (plugin) arg("ref").orEmpty().ifBlank { return null } else MagisRef(id, type, 0).encode()
    return CatalogItem(
        id = id,
        title = arg("title").orEmpty(),
        poster = arg("poster")?.takeIf { it.isNotBlank() },
        durationS = arg("duration")?.toIntOrNull() ?: 0,
        adult = arg("adult") == "1",
        ref = ref,
        type = type,
        genres = arg("genres").orEmpty().split("|").filter { it.isNotBlank() },
        score = arg("score")?.toDoubleOrNull(),
        backdrop = arg("backdrop")?.takeIf { it.isNotBlank() },
        description = arg("desc").orEmpty(),
        episodeCount = arg("count")?.toIntOrNull() ?: 0,
    )
}

/** Which source a route opens: Magis unless it says `src=plugin`. */
fun titleOriginFrom(arg: (String) -> String?): TitleOrigin =
    if (arg("src") == "plugin") {
        TitleOrigin.Plugin(
            PluginTitleExtras(
                pluginName = arg("plugin").orEmpty(),
                color = arg("color").orEmpty(),
                year = arg("year").orEmpty(),
                tmdbId = arg("tmdb")?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
                imdbId = arg("imdb").orEmpty(),
            ),
        )
    } else {
        TitleOrigin.Magis
    }
