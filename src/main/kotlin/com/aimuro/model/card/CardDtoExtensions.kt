package com.aimuro.model.card

internal fun CardResponse.toCondensedString(): String {
    return listOfNotNull(
        name.cardInfoFormat("NAME"),
        code.cardInfoFormat("CARD NUMBER"),
        level.cardInfoFormat("LV"),
        cost.cardInfoFormat("COST"),
        color.cardInfoFormat("COLOR"),
        cardType.cardInfoFormat("TYPE"),
        trait.cardInfoFormat("TRAIT(S)"),
        ap.cardInfoFormat("AP"),
        hp.cardInfoFormat("HP"),
        link.cardInfoFormat("LINK CONDITION"),
        effect.cardInfoFormat("EFFECT"),
    ).joinToString(" | ")
}

fun String?.cardInfoFormat(type: String): String? = this?.takeUnless { it.isEmpty() || it == "-" }?.let { "$type: $it" }


fun List<CardResponse>.toCondensedString(): String = joinToString { "${it.toCondensedString()}\n" }

