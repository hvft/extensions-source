import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai (API)"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "all"
        baseUrl = "https://nhentai.net"
        // Pinned: the source id must never change, or existing library entries
        // turn into "unknown source" after an update.
        id = 6100451287633418274L
    }
}
