package com.arkiv.player.ui.titleinfo

import com.arkiv.player.data.gateway.CatalogItem
import com.arkiv.player.data.gateway.GatewayResult
import com.arkiv.player.data.magis.MagisRef
import java.net.URLEncoder

/** How many characters of a synopsis travel in the route. See [clipSynopsis]. */
const val TITLE_DESC_MAX = 600

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
 * The route of a Magis card's info page, or null when the card has no id (a blank id would make
 * `title/` and crash Navigation). Everything the page paints first travels in the route, so it
 * survives the system killing the process while the player is on top.
 */
fun titleRoute(item: CatalogItem): String? {
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
        "&desc=${enc(clipSynopsis(item.description))}"
}

/** The route for a Magis search result, or null when it is not a Magis title. */
fun titleRoute(result: GatewayResult): String? = result.toMagisCatalogItem()?.let { titleRoute(it) }

/**
 * Rebuilds the [CatalogItem] a title route carried. [arg] returns one argument already URL-decoded,
 * which is what `entry.arguments?.getString(name)` gives; taking a function keeps this free of
 * Android types. Null when the id is blank.
 *
 * The ref is rebuilt from id and type because that is exactly what the portal's cards carry
 * (`MagisRef(id, type, 0).encode()`).
 */
fun titleItemFrom(arg: (String) -> String?): CatalogItem? {
    val id = arg("id").orEmpty()
    if (id.isBlank()) return null
    val type = arg("type").orEmpty().ifBlank { "movie" }
    return CatalogItem(
        id = id,
        title = arg("title").orEmpty(),
        poster = arg("poster")?.takeIf { it.isNotBlank() },
        durationS = arg("duration")?.toIntOrNull() ?: 0,
        adult = arg("adult") == "1",
        ref = MagisRef(id, type, 0).encode(),
        type = type,
        genres = arg("genres").orEmpty().split("|").filter { it.isNotBlank() },
        score = arg("score")?.toDoubleOrNull(),
        backdrop = arg("backdrop")?.takeIf { it.isNotBlank() },
        description = arg("desc").orEmpty(),
        episodeCount = arg("count")?.toIntOrNull() ?: 0,
    )
}
