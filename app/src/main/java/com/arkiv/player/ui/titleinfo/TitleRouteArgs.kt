package com.arkiv.player.ui.titleinfo

import androidx.navigation.NamedNavArgument
import androidx.navigation.NavType
import androidx.navigation.navArgument

/** Pattern of the info page's route; build one with [titleRoute]. Same on the phone and the TV. */
const val TITLE_ROUTE = "title/{id}?type={type}&title={title}&poster={poster}&backdrop={backdrop}" +
    "&count={count}&score={score}&genres={genres}&duration={duration}&adult={adult}&desc={desc}" +
    "&src={src}&ref={ref}&plugin={plugin}&color={color}&year={year}&tmdb={tmdb}&imdb={imdb}"

/** Every argument is a string: numbers and the adult flag are parsed by [titleItemFrom]. */
val titleRouteArguments: List<NamedNavArgument> =
    listOf(navArgument("id") { type = NavType.StringType }) +
        listOf(
            "type", "title", "poster", "backdrop", "count", "score", "genres", "duration", "adult", "desc",
            "src", "ref", "plugin", "color", "year", "tmdb", "imdb",
        ).map { name -> navArgument(name) { type = NavType.StringType; defaultValue = "" } }
