package com.ticketrush.shared

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter
class PhoneHashConverter : AttributeConverter<PhoneHash?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: PhoneHash?): ByteArray? = attribute?.value

    override fun convertToEntityAttribute(dbData: ByteArray?): PhoneHash? = dbData?.let { PhoneHash(it) }
}
