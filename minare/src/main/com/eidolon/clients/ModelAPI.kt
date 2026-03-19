package com.eidolon.clients

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelAPI @Inject constructor(
    private val vertx: Vertx
) {
    private val log = LoggerFactory.getLogger(ModelAPI::class.java)
    private val apiKey: String
    private val nanoModel: String
    private val httpClient: HttpClient

    init {
        val props = Properties()
        val stream = javaClass.classLoader.getResourceAsStream("models.cfg")
        if (stream != null) {
            props.load(stream)
            stream.close()
        } else {
            log.warn("models.cfg not found on classpath — ModelAPI will not function")
        }
        apiKey = props.getProperty("openai.api_key", "")
        nanoModel = props.getProperty("openai.nano_model", "gpt-4o-mini")

        httpClient = vertx.createHttpClient(
            HttpClientOptions()
                .setSsl(true)
                .setDefaultHost("api.openai.com")
                .setDefaultPort(443)
        )

        if (apiKey.isBlank() || apiKey == "YOUR_KEY_HERE") {
            log.warn("ModelAPI: No valid API key configured in models.cfg")
        } else {
            log.info("ModelAPI: Initialized with model {}", nanoModel)
        }
    }

    fun query(systemPrompt: String, message: String): CompletableDeferred<String> {
        val deferred = CompletableDeferred<String>()

        val body = JsonObject()
            .put("model", nanoModel)
            .put("messages", JsonArray()
                .add(JsonObject().put("role", "system").put("content", systemPrompt))
                .add(JsonObject().put("role", "user").put("content", message))
            )

        val bodyBytes = body.toBuffer()

        httpClient.request(HttpMethod.POST, "/v1/chat/completions")
            .onSuccess { req ->
                req.putHeader("Authorization", "Bearer $apiKey")
                req.putHeader("Content-Type", "application/json")
                req.putHeader("Content-Length", bodyBytes.length().toString())

                req.send(bodyBytes)
                    .onSuccess { resp ->
                        resp.body()
                            .onSuccess { buf ->
                                try {
                                    val json = JsonObject(buf)
                                    val choices = json.getJsonArray("choices")
                                    if (choices != null && choices.size() > 0) {
                                        val content = choices.getJsonObject(0)
                                            .getJsonObject("message")
                                            .getString("content", "")
                                        deferred.complete(content)
                                    } else {
                                        val error = json.getJsonObject("error")
                                        val errorMsg = error?.getString("message") ?: "No choices in response"
                                        log.error("ModelAPI query failed: {}", errorMsg)
                                        deferred.completeExceptionally(RuntimeException(errorMsg))
                                    }
                                } catch (e: Exception) {
                                    log.error("ModelAPI: Failed to parse response", e)
                                    deferred.completeExceptionally(e)
                                }
                            }
                            .onFailure { err ->
                                log.error("ModelAPI: Failed to read response body", err)
                                deferred.completeExceptionally(err)
                            }
                    }
                    .onFailure { err ->
                        log.error("ModelAPI: Request send failed", err)
                        deferred.completeExceptionally(err)
                    }
            }
            .onFailure { err ->
                log.error("ModelAPI: Failed to create request", err)
                deferred.completeExceptionally(err)
            }

        return deferred
    }
}
