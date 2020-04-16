package apoc.ai.azure

import apoc.ai.service.AI
import apoc.result.MapResult
import apoc.util.JsonUtil
import org.neo4j.logging.Log
import java.io.DataOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection


private fun convertInput(data: Any): List<Map<String, Any?>> {
    return when (data) {
        is Map<*, *> -> convertInputFromMap(data as Map<String, Any>)
        is Collection<*> -> convertInputFromCollection(data)
        is String -> convertInputFromMap(mapOf("id" to 1, "text" to data))
        else -> throw RuntimeException("Class ${data::class.java.name} not supported")
    }
}

private fun convertInputFromCollection(data: Collection<*>): List<Map<String, Any?>> {
    if (data.isEmpty()) {
        return emptyList()
    }
    return data.filterNotNull().mapIndexed { index, element ->
        when (element) {
            is Map<*, *> -> element as Map<String, Any>
            is String -> mapOf("id" to index.toString(), "text" to element)
            else -> throw RuntimeException("Class ${element::class.java.name} not supported")
        }
    }
}

private fun convertInputFromMap(data: Map<String, Any>): List<Map<String, Any?>> {
    if (data.isEmpty()) {
        return emptyList()
    }
    return listOf(data)
}

enum class AzureEndpoint(val method: String,
                         val createRequest: (Any) -> Map<String, Any> = { data: Any -> mapOf("documents" to convertInput(data)) },
                         val parseResponse: (Any) -> List<MapResult> = { result: Any -> ((result as Map<String, Any?>)["documents"] as List<Map<String, Any?>>).map { MapResult(it) } }) {
    SENTIMENT("/text/analytics/v2.1/sentiment"),
    KEY_PHRASES("/text/analytics/v2.1/keyPhrases"),
    VISION("/vision/v2.1/analyze",
            { data: Any -> when (data) {
                is Map<*, *> -> data as Map<String, Any>
                is String -> mapOf("url" to data)
                else -> throw RuntimeException("Class ${data::class.java.name} not supported")
            } },
            { result: Any -> listOf(((result as Map<String, Any?>).let { MapResult(it) })) }),
    ENTITIES("/text/analytics/v2.1/entities");

    fun createFullUrl(baseUrl: String, params: Map<String, Any?>): URL {
        val fullUrl = baseUrl.let { if (it.endsWith("/")) it.substring(0, it.lastIndex) else it } + this.method + params.map { "${it.key}=${it.value}" }
                .joinToString("&")
                .also { if (it.isNullOrBlank()) it else "?$it" }
        return URL(fullUrl)
    }
}

class AzureClient(private val baseUrl: String, private val key: String, private val log: Log): AI {

    private fun postData(endpoint: AzureEndpoint, subscriptionKeyValue: String, data: Any, config: Map<String, Any?> = emptyMap()): List<MapResult> {
        val connection = endpoint.createFullUrl(baseUrl, config).openConnection() as HttpsURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Ocp-Apim-Subscription-Key", subscriptionKeyValue)
        connection.doOutput = true
        when (data) {
            is ByteArray -> {
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                DataOutputStream(connection.outputStream).use { it.write(data) }
            }
            else -> {
                connection.setRequestProperty("Content-Type", "application/json")
                DataOutputStream(connection.outputStream).use { it.write(JsonUtil.OBJECT_MAPPER.writeValueAsBytes(endpoint.createRequest(data))) }
            }
        }
        return connection.inputStream
                .use { JsonUtil.OBJECT_MAPPER.readValue(it, Any::class.java) }
                .let { result -> endpoint.parseResponse(result) }
    }

    override fun entities(data: Any, config: Map<String, Any?>): List<MapResult> = postData(AzureEndpoint.ENTITIES, key, data)

    override fun sentiment(data: Any, config: Map<String, Any?>): List<MapResult> = postData(AzureEndpoint.SENTIMENT, key, data)

    override fun keyPhrases(data: Any, config: Map<String, Any?>): List<MapResult> = postData(AzureEndpoint.KEY_PHRASES, key, data)

    override fun vision(data: Any, config: Map<String, Any?>): List<MapResult> = postData(AzureEndpoint.VISION, key, data, config)
}