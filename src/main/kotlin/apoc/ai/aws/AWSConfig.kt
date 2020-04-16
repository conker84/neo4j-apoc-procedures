package apoc.ai.aws

import apoc.result.MapResult
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder
import com.amazonaws.services.rekognition.model.DetectLabelsRequest
import com.amazonaws.services.rekognition.model.Image
import java.nio.ByteBuffer

private fun readData(url: String): ByteBuffer {
    val url = java.net.URL(url)
    return ByteBuffer.wrap(url.openStream().readAllBytes())
}

enum class RekognitionType(val doRequest: (Any, AWSCredentialsProvider, AWSConfig) -> List<MapResult>) {
    DETECT_LABELS({ input, credentialsProvider, awsConfig ->
        val image = when (input) {
            is String -> readData(input)
            is Map<*, *> -> readData(input["url"] as String)
            is ByteArray -> ByteBuffer.wrap(input)
            else -> throw RuntimeException("Class ${input::class.java.name} not supported")
        }
        val request = DetectLabelsRequest().withImage(Image().withBytes(image))
                .withMaxLabels(awsConfig.maxLabels)
        val clientConfig = ClientConfiguration()
        clientConfig.connectionTimeout = awsConfig.connectionTimeout
        clientConfig.requestTimeout = awsConfig.requestTimeout
        clientConfig.protocol = Protocol.HTTPS
        val client = AmazonRekognitionClientBuilder
                .standard()
                .withClientConfiguration(clientConfig)
                .withCredentials(credentialsProvider)
                .withRegion(awsConfig.region)
                .build()
        val result = client.detectLabels(request)
        listOf(MapResult(mapOf("labels" to result.labels.map { apoc.util.JsonUtil.OBJECT_MAPPER.convertValue(it, Map::class.java) })))
    })
}

data class AWSConfig(val region: String = "us-east-2",
                     val language: String = "en",
                     val connectionTimeout: Int = 30000,
                     val requestTimeout: Int = 60000,
                     val rekognitionType: RekognitionType = RekognitionType.DETECT_LABELS,
                     val maxLabels: Int = 10) {
    companion object {
        val DEFAULT = AWSConfig()
    }

    constructor(map: Map<String, Any?>) : this(map.getOrDefault("region", DEFAULT.region) as String,
            map.getOrDefault("language", DEFAULT.language) as String,
            map.getOrDefault("connectionTimeout", DEFAULT.connectionTimeout) as Int,
            map.getOrDefault("requestTimeout", DEFAULT.requestTimeout) as Int,
            RekognitionType.valueOf(map.getOrDefault("rekognitionType", DEFAULT.rekognitionType.name) as String),
            map.getOrDefault("maxLabels", DEFAULT.maxLabels) as Int)

}