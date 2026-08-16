package dev.klaiber.cirrus.ui

/**
 * The headings of the settings screen, named once.
 *
 * `SettingSwitch.path` tells a model — and through it the user — to go to "Settings → Tools →
 * Memory". That direction is only worth giving while a section by that name exists, so the screen
 * takes its headings from here and `SettingsCatalogTest` checks every path against this list.
 * Renaming a section then breaks a test rather than quietly sending somebody nowhere.
 *
 * There are no icons and no summaries: the desktop screen is one scrolling page rather than
 * Android's list of sub-screens, so a heading is all a section needs.
 */
enum class SettingsSection(val title: String) {
    CONNECTION("Connection"),
    MODEL("Model"),
    TOOLS("Tools"),
    GITHUB("GitHub"),
    APPEARANCE("Appearance"),
}
