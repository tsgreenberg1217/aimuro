package com.aimuro.model.card

internal fun CardResponse.toCondensedString(): String {
    return listOfNotNull(
        name?.let { "NAME: $it" },
        code?.let { "CARD NUMBER: $it" },
        level?.let { "LV: $it" },
        cost?.let { "COST: $it" },
        color?.let { "COLOR: $it" },
        cardType?.let { "TYPE: $it" },
        trait?.let { "TRAIT(S): $it" },
        ap?.let { "AP: $it" },
        hp?.let { "HP: $it" },
        link?.let { "LINK CONDITION: $it" },
        effect?.let { "EFFECT: $it" },
    ).joinToString(" | ")
}

fun List<CardResponse>.toCondensedString(): String = joinToString { "${it.toCondensedString()}\n" }

