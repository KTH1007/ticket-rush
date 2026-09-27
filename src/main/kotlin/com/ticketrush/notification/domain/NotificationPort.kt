package com.ticketrush.notification.domain

interface NotificationPort {
    fun sendSms(
        phone: String,
        message: String,
    )
}
