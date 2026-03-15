package com.aimuro.aimuro.card

fun CardDto.toCondensedString(): String {
    val stats = listOfNotNull(
        code?.let { "LV:$it" },
        level?.let { "LV:$it" },
        cost?.let { "COST:$it" },
        color?.let { "COLOR:$it" },
        cardType?.let { "COLOR:$it" },
        trait?.let { "COLOR:$it" },
        ap?.let { "AP:$it" },
        hp?.let { "HP:$it" },
        link?.let { "COLOR:$it" },
    ).joinToString(" ")

    return listOfNotNull(
        name,
        stats.ifBlank { null },
        color,
        trait,
        effect,
    ).joinToString(" | ")
}
