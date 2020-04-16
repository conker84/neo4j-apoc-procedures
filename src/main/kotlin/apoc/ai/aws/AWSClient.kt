package apoc.ai.aws

import apoc.ai.service.AI
import apoc.result.MapResult
import apoc.util.JsonUtil
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.comprehend.AmazonComprehendClientBuilder
import com.amazonaws.services.comprehend.model.BatchDetectEntitiesRequest
import com.amazonaws.services.comprehend.model.BatchDetectKeyPhrasesRequest
import com.amazonaws.services.comprehend.model.BatchDetectSentimentRequest
import org.neo4j.logging.Log


class AWSClient(private val apiKey: String, private val apiSecret: String, private val log: Log): AI {

    private val credentialsProvider = AWSStaticCredentialsProvider(BasicAWSCredentials(apiKey, apiSecret))

    private fun buildClient(config: AWSConfig) = AmazonComprehendClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion(config.region)
            .build()

    override fun entities(data: Any, config: Map<String, Any?>): List<MapResult> {
        val awsConfig = AWSConfig(config)
        val awsClient = buildClient(awsConfig)
        val convertedData = convertInput(data)
        var batch = BatchDetectEntitiesRequest().withTextList(convertedData)
                .withLanguageCode(awsConfig.language)
        var batchDetectEntities = awsClient.batchDetectEntities(batch)

        val allData = batchDetectEntities.resultList
        val batchToRetry = batchDetectEntities.errorList.map { convertedData[it.index] }
        if (batchToRetry.isNotEmpty()) {
            batch = BatchDetectEntitiesRequest().withTextList(batchToRetry)
            batchDetectEntities = awsClient.batchDetectEntities(batch)
            allData += batchDetectEntities.resultList
        }

        return allData.map { MapResult(JsonUtil.OBJECT_MAPPER.convertValue(it, Map::class.java) as Map<String, Any?>) }
    }

    override fun sentiment(data: Any, config: Map<String, Any?>): List<MapResult> {
        val awsConfig = AWSConfig(config)
        val awsClient = buildClient(awsConfig)
        val convertedData = convertInput(data)
        var batch = BatchDetectSentimentRequest().withTextList(convertedData)
                .withLanguageCode(awsConfig.language)
        var batchDetectEntities = awsClient.batchDetectSentiment(batch)

        val allData = batchDetectEntities.resultList
        val batchToRetry = batchDetectEntities.errorList.map { convertedData[it.index] }
        if (batchToRetry.isNotEmpty()) {
            batch = BatchDetectSentimentRequest().withTextList(batchToRetry)
            batchDetectEntities = awsClient.batchDetectSentiment(batch)
            allData += batchDetectEntities.resultList
        }

        return allData.map { MapResult(JsonUtil.OBJECT_MAPPER.convertValue(it, Map::class.java) as Map<String, Any?>) }
    }

    override fun keyPhrases(data: Any, config: Map<String, Any?>): List<MapResult> {
        val awsConfig = AWSConfig(config)
        val awsClient = buildClient(awsConfig)
        val convertedData = convertInput(data)
        var batch = BatchDetectKeyPhrasesRequest().withTextList(convertedData)
                .withLanguageCode(awsConfig.language)
        var batchDetectEntities = awsClient.batchDetectKeyPhrases(batch)

        val allData = batchDetectEntities.resultList
        val batchToRetry = batchDetectEntities.errorList.map { convertedData[it.index] }
        if (batchToRetry.isNotEmpty()) {
            batch = BatchDetectKeyPhrasesRequest().withTextList(batchToRetry)
            batchDetectEntities = awsClient.batchDetectKeyPhrases(batch)
            allData += batchDetectEntities.resultList
        }

        return allData.map { MapResult(JsonUtil.OBJECT_MAPPER.convertValue(it, Map::class.java) as Map<String, Any?>) }
    }

    override fun vision(data: Any, config: Map<String, Any?>): List<MapResult> {
        val awsConfig = AWSConfig(config)
        return awsConfig.rekognitionType.doRequest(data, credentialsProvider, awsConfig)
    }

    private fun convertInput(data: Any): List<String> {
        return when (data) {
            is Map<*, *> -> {
                val map = data as Map<String, String>
                val list = arrayOfNulls<String>(map.size)
                map.mapKeys { it.key.toInt() }
                        .forEach { k, v -> list[k] = v }
                list.mapNotNull { it ?: "" }.toList()
            }
            is Collection<*> -> data as List<String>
            is String -> listOf(data)
            else -> throw RuntimeException("Class ${data::class.java.name} not supported")
        }
    }
}