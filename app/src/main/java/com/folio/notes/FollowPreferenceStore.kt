package com.folio.notes

import android.content.SharedPreferences

/** One mapping shared by Settings and every editor; existing preference keys remain intact. */
internal object FollowPreferenceStore {
    val keys = arrayOf("follow.direction", "follow.mode", "follow.autoReturn", "follow.position", "follow.horizontal",
        "follow.spacing", "follow.returnDelayMs", "follow.glideMs", "follow.lineSpeedMs", "follow.adaptiveSpacing",
        "follow.horizontalFollow", "follow.verticalFollow", "follow.autoSwitchAreas", "follow.minimumZoom",
        "follow.edgeThreshold", "follow.verticalDeadBand", "follow.endMargin", "follow.showLanding", AppPrefs.FOLLOW_EDGE_STRIP_WIDTH)

    private inline fun <reified E : Enum<E>> enum(p: SharedPreferences, key: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == p.getString(key, null) } ?: default
    private fun float(p: SharedPreferences, key: String, default: Float, min: Float, max: Float): Float =
        p.getFloat(key, default).takeIf(Float::isFinite)?.coerceIn(min, max) ?: default
    fun hand(p: SharedPreferences): WritingHand = enum(p, "writingHand", WritingHand.RIGHT)
    fun read(p: SharedPreferences) = FollowPreferences(
        direction = enum(p, "follow.direction", WritingDirection.LTR),
        mode = enum(p, "follow.mode", FollowMode.TEXT),
        automaticReturn = p.getBoolean("follow.autoReturn", false),
        position = float(p, "follow.position", .55f, .35f, .7f),
        horizontalPosition = float(p, "follow.horizontal", .5f, .35f, .65f),
        spacing = float(p, "follow.spacing", 32f, FollowPreferences.MIN_SPACING, FollowPreferences.MAX_SPACING),
        returnDelayMs = p.getInt("follow.returnDelayMs", WritingFollow.DEFAULT_RETURN_MS).coerceIn(300, 2000),
        glideDurationMs = p.getInt("follow.glideMs", WritingFollow.DEFAULT_GLIDE_MS).coerceIn(120, 800),
        lineSpeedMs = p.getInt("follow.lineSpeedMs", FollowGlide.MS_PER_VIEWPORT.toInt()).coerceIn(250, 1500),
        adaptiveSpacing = p.getBoolean("follow.adaptiveSpacing", true),
        horizontalFollow = p.getBoolean("follow.horizontalFollow", true),
        verticalFollow = p.getBoolean("follow.verticalFollow", true),
        autoSwitchAreas = p.getBoolean("follow.autoSwitchAreas", true),
        minimumZoom = float(p, "follow.minimumZoom", 1.4f, 1f, 3f),
        edgeThreshold = float(p, "follow.edgeThreshold", .72f, .55f, .95f),
        verticalDeadBand = float(p, "follow.verticalDeadBand", .15f, .05f, .3f),
        endMargin = float(p, "follow.endMargin", .08f, .02f, .2f),
        showLandingGuide = p.getBoolean("follow.showLanding", true),
        edgeStripWidth = AppPrefs.followEdgeStripWidth(p.getFloat(AppPrefs.FOLLOW_EDGE_STRIP_WIDTH, AppPrefs.DEFAULT_FOLLOW_EDGE_STRIP_WIDTH)),
    )

    fun write(p: SharedPreferences, value: FollowPreferences) = p.write {
        putString("follow.direction", value.direction.name)
        putString("follow.mode", value.mode.name)
        putBoolean("follow.autoReturn", value.automaticReturn)
        putFloat("follow.position", value.position)
        putFloat("follow.horizontal", value.horizontalPosition)
        putFloat("follow.spacing", value.spacing)
        putInt("follow.returnDelayMs", value.returnDelayMs)
        putInt("follow.glideMs", value.glideDurationMs)
        putInt("follow.lineSpeedMs", value.lineSpeedMs)
        putBoolean("follow.adaptiveSpacing", value.adaptiveSpacing)
        putBoolean("follow.horizontalFollow", value.horizontalFollow)
        putBoolean("follow.verticalFollow", value.verticalFollow)
        putBoolean("follow.autoSwitchAreas", value.autoSwitchAreas)
        putFloat("follow.minimumZoom", value.minimumZoom)
        putFloat("follow.edgeThreshold", value.edgeThreshold)
        putFloat("follow.verticalDeadBand", value.verticalDeadBand)
        putFloat("follow.endMargin", value.endMargin)
        putBoolean("follow.showLanding", value.showLandingGuide)
        putFloat(AppPrefs.FOLLOW_EDGE_STRIP_WIDTH, AppPrefs.followEdgeStripWidth(value.edgeStripWidth))
    }
}
