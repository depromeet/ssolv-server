package org.depromeet.team3.util

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule

object GenericDataEncoder {

    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())

    fun <T> encodeObject(data: T): String? = try {
        val json = objectMapper.writeValueAsString(data)
        java.util.Base64.getEncoder().encodeToString(json.toByteArray())
    } catch (e: Exception) {
        null
    }

    fun <T> decodeObject(encodedData: String, clazz: Class<T>): T? = try {
        val json = String(java.util.Base64.getDecoder().decode(encodedData))
        objectMapper.readValue(json, clazz)
    } catch (e: Exception) {
        null
    }

    fun encodeMap(data: Map<String, Any>): String? = encodeObject(data)

    @Suppress("UNCHECKED_CAST")
    fun decodeMap(encodedData: String): Map<String, Any>? = try {
        val json = String(java.util.Base64.getDecoder().decode(encodedData))
        objectMapper.readValue<Map<String, Any>>(
            json,
            objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, Any::class.java),
        )
    } catch (e: Exception) {
        null
    }

    fun <T> encodeList(data: List<T>): String? = encodeObject(data)

    fun <T> decodeList(encodedData: String, clazz: Class<T>): List<T>? = try {
        val json = String(java.util.Base64.getDecoder().decode(encodedData))
        objectMapper.readValue<List<T>>(json, objectMapper.typeFactory.constructCollectionType(List::class.java, clazz))
    } catch (e: Exception) {
        null
    }
}
