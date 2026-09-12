package com.ticketrush.event.presentation

import com.ticketrush.reservation.domain.Grade

data class GradeResponse(
    val name: String,
    val price: Int,
) {
    companion object {
        fun from(grade: Grade): GradeResponse = GradeResponse(name = grade.name, price = grade.price)
    }
}
