package com.aimuro.aimuro.model.card

data class CardFilterQuery(
    val level: String? = null,
    val cost: String? = null,
    val color: String? = null,
    val unit: String? = null,
)
