package org.depromeet.team3.meetingattendee

enum class MuzziColor {
    NONE,
    DEFAULT,
    BANANA,
    BROCCOLI,
    CARROT,
    LEMON,
    MUSHROOM,
    PAPRIKA,
    PEAR,
    TOMATO,
    TURNIP,
    ;

    companion object {
        fun getOrDefault(name: String?): MuzziColor = if (name.isNullOrBlank()) {
            NONE
        } else {
            try {
                valueOf(name.uppercase())
            } catch (e: IllegalArgumentException) {
                NONE
            }
        }
    }
}
