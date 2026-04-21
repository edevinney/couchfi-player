package com.couchfi.player.library

data class RadioStation(
    val name:        String,
    val url:         String,
    val description: String? = null,
    /** SMB-relative path to a logo image, or null. Not rendered yet. */
    val logo:        String? = null,
    /** Path of the YAML file the station came from (for debugging). */
    val source:      String,
)
