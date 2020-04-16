package apoc.ai.azure

import apoc.ai.aws.AWSProcedures
import apoc.util.TestUtil
import org.junit.Assert
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.neo4j.test.rule.ImpermanentDbmsRule
import java.util.stream.Collectors

class AWSProceduresTest {
    companion object {
        @JvmStatic val AWS_KEY = System.getenv("AWS_KEY")
        @JvmStatic val AWS_SECRET = System.getenv("AWS_SECRET")

        @ClassRule
        @JvmField
        val neo4j = ImpermanentDbmsRule()

        @BeforeClass
        @JvmStatic
        fun beforeClass() {
            TestUtil.registerProcedure(neo4j, AWSProcedures::class.java)
            Assume.assumeTrue(AWS_KEY != null && AWS_SECRET != null)
        }
    }

    @Test
    fun `should provide sentiment analysis`() {
        // given
        val data = listOf("This is a bad day", "This is a good day")

        // when
        neo4j.executeTransactionally("CALL apoc.ai.aws.sentiment(\$key, \$secret, \$data)",
                mapOf("key" to AWS_KEY, "secret" to AWS_SECRET, "data" to data)) {

            // then
            val response = it.columnAs<Map<String, Any>>("value")
                    .stream()
                    .collect(Collectors.toList())
            val negative = response[0]
            val positive = response[1]
            Assert.assertEquals("NEGATIVE", negative["sentiment"])
            Assert.assertEquals(0L, negative["index"])
            Assert.assertEquals("POSITIVE", positive["sentiment"])
            Assert.assertEquals(1L, positive["index"])
        }
    }

    @Test
    fun `should provide key phrases analysis`() {
        // given
        val data = listOf("""
            "Covid-19 anywhere is Covid-19 everywhere," Melinda Gates said as she called for global co-operation to beat the pandemic.
            The philanthropist was speaking to Emma Barnett on BBC Radio 5 Live after President Donald Trump announced the US would stop funding the World Health Organization (WHO).
            The Bill and Melinda Gates Foundation - the second-largest funder of the WHO - has pledged a further ${'$'}150m (£120m) to fight Covid-19, but she said they did not expect a vaccine to be available for 18 months.
        """.trimIndent())

        // when
        neo4j.executeTransactionally("CALL apoc.ai.aws.keyPhrases(\$key, \$secret, \$data)",
                mapOf("key" to AWS_KEY, "secret" to AWS_SECRET, "data" to data)) {

            // then
            val response = it.columnAs<Map<String, Any>>("value")
            val map = response.stream().findFirst().get()
            val expected = listOf("Covid-19", "Covid-19", "Melinda Gates", "global co-operation",
                    "the pandemic", "The philanthropist", "Emma Barnett", "BBC Radio 5 Live", "President Donald Trump",
                    "the US", "the World Health Organization", "The Bill and Melinda Gates Foundation",
                    "the second-largest funder", "the WHO", "a further $150m", "£120m",
                    "Covid-19", "a vaccine", "18 months")
            val result = (map["keyPhrases"] as List<Map<String, Any>>)
                    .mapNotNull { map -> map["text"] as String }
            Assert.assertEquals(expected, result)
        }
    }

    @Test
    fun `should provide entity analysis`() {
        // given
        val data = listOf("I had a wonderful trip to Seattle last week.")

        // when
        neo4j.executeTransactionally("CALL apoc.ai.aws.entities(\$key, \$secret, \$data)",
                mapOf("key" to AWS_KEY, "secret" to AWS_SECRET, "data" to data)) {

            // then
            val response = it.columnAs<Map<String, Any>>("value")
            val map = response.stream().findFirst().get()
            val entities = map["entities"] as List<Map<String, Any>>
            val resultEntities = entities.map { it["text"] as String }
            val expected = listOf("Seattle", "last week")
            Assert.assertEquals(expected, resultEntities)
        }
    }

    @Test
    fun `should provide computer vision analysis`() {
        // given
        val data = "https://staticfanpage.akamaized.net/wp-content/uploads/sites/12/2018/10/640px-torre_di_pisa_vista_dal_cortile_dellopera_del_duomo_06-638x425.jpg"

        // when
        neo4j.executeTransactionally("CALL apoc.ai.aws.vision(\$key, \$secret, \$data)",
                mapOf("key" to AWS_KEY, "secret" to AWS_SECRET, "data" to data)) {

            // then
            val response = it.columnAs<Map<String, Any>>("value")
            val map = response.stream().findFirst().get()
            val categories = map["labels"] as List<Map<String, Any>>
            val resultEntities = categories.map { it["name"] as String }.toSet()
            val expected = setOf("Building", "Architecture", "Tower", "Bell Tower", "Steeple", "Spire")
            Assert.assertEquals(expected, resultEntities)
        }
    }


}
