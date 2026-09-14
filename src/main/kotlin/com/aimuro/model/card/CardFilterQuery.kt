package com.aimuro.model.card

data class CardFilterQuery(
    val level: String? = null,
    val cost: String? = null,
    val color: String? = null,
    val cardType: String? = null,
    val trait: String? = null,
)
