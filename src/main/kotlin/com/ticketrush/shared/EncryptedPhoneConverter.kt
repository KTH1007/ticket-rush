package com.ticketrush.shared

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class EncryptedPhoneConverter : AttributeConverter<EncryptedPhone?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: EncryptedPhone?): ByteArray? = attribute?.value

    override fun convertToEntityAttribute(dbData: ByteArray?): EncryptedPhone? = dbData?.let { EncryptedPhone(it) }
}
