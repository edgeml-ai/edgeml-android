package ai.octomil.responses.runtime

import ai.octomil.chat.GenerateConfig
import ai.octomil.chat.LLMRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LLMRuntimeAdapterTest {

    @Test
    fun `run collects all tokens and returns response`() = runTest {
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                emit("Hello")
                emit(" world")
            }
            override fun close() {}
        }

        val adapter = LLMRuntimeAdapter(llm)
        val response = adapter.run(RuntimeRequest(prompt = "test"))

        assertEquals("Hello world", response.text)
        assertEquals("stop", response.finishReason)
        assertNotNull(response.usage)
        assertEquals(2, response.usage!!.completionTokens)
    }

    @Test
    fun `stream emits chunks for each token`() = runTest {
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                emit("token1")
                emit("token2")
                emit("token3")
            }
            override fun close() {}
        }

        val adapter = LLMRuntimeAdapter(llm)
        val chunks = adapter.stream(RuntimeRequest(prompt = "test")).toList()

        assertEquals(3, chunks.size)
        assertEquals("token1", chunks[0].text)
        assertEquals("token2", chunks[1].text)
        assertEquals("token3", chunks[2].text)
    }

    @Test
    fun `run passes config to LLMRuntime`() = runTest {
        var capturedConfig: GenerateConfig? = null
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {
                capturedConfig = config
                emit("ok")
            }
            override fun close() {}
        }

        val adapter = LLMRuntimeAdapter(llm)
        adapter.run(RuntimeRequest(
            prompt = "test",
            maxTokens = 100,
            temperature = 0.5f,
            topP = 0.9f,
            stop = listOf("END"),
        ))

        assertNotNull(capturedConfig)
        assertEquals(100, capturedConfig!!.maxTokens)
        assertEquals(0.5f, capturedConfig!!.temperature)
        assertEquals(0.9f, capturedConfig!!.topP)
        assertEquals(listOf("END"), capturedConfig!!.stop)
    }

    @Test
    fun `close delegates to LLMRuntime`() {
        var closed = false
        val llm = object : LLMRuntime {
            override fun generate(prompt: String, config: GenerateConfig): Flow<String> = flow {}
            override fun close() { closed = true }
        }

        val adapter = LLMRuntimeAdapter(llm)
        adapter.close()
        assert(closed)
    }
}
