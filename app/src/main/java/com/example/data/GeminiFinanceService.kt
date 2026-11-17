package com.example.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val APP_CHECK_TAG = "AppCheckInit"
private const val AI_SERVICE_TAG = "GeminiFinanceService"

// Claves de contrato de App Check obligatorias
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

/**
 * Configura Firebase App Check antes de cualquier operación de UI o inferencia de Gemini.
 * Usa .commit() síncrono en modo debug para evitar condiciones de carrera con el token de prueba.
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // CRÍTICO: Usar commit() síncrono para que el token esté disponible de inmediato
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(APP_CHECK_TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(APP_CHECK_TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(APP_CHECK_TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    Log.d(APP_CHECK_TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(APP_CHECK_TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(APP_CHECK_TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(APP_CHECK_TAG, "Failed to initialize Firebase App Check", e)
    }
}

/**
 * Resultado estructurado cuando Gemini interpreta texto/voz o una foto de recibo.
 */
data class ParsedMovementDraft(
    val type: TransactionType,
    val amount: Long,
    val categoryName: String,
    val accountName: String,
    val note: String,
    val dateDescription: String = "Hoy"
)

/**
 * Resumen semanal o mensual generado en JSON estructurado por Gemini.
 */
data class AiFinancialReport(
    val periodTitle: String,
    val whatImproved: String,
    val whatWorsened: String,
    val recommendations: List<String>,
    val shortDailyTip: String
)

/**
 * Servicio de Inteligencia Artificial con Firebase AI Logic (gemini-3.8-flash).
 * Nunca expone una API Key en el APK y envía únicamente resúmenes agregados por privacidad.
 */
class GeminiFinanceService {

    // 1. Modelo general para chat conversacional en streaming
    private val chatModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    // 2. Esquema JSON y modelo para convertir texto/voz o foto de recibo en un movimiento
    private val movementDraftSchema = Schema.obj(
        properties = mapOf(
            "tipo" to Schema.enumeration(
                values = listOf("INGRESO", "GASTO", "TRANSFERENCIA"),
                description = "Tipo de movimiento financiero"
            ),
            "montoCop" to Schema.integer(
                description = "Monto entero en pesos colombianos (ej: '25 mil' -> 25000, 'un millón doscientos' -> 1200000)"
            ),
            "categoria" to Schema.string(
                description = "Nombre exacto de la categoría más adecuada"
            ),
            "cuenta" to Schema.string(
                description = "Nombre de la cuenta mencionada (Efectivo, Nu, Nequi, etc.)"
            ),
            "nota" to Schema.string(
                description = "Descripción corta del gasto, ingreso o comercio"
            ),
            "fechaTexto" to Schema.string(
                description = "Referencia de fecha detectada (ej. Hoy, Ayer, o fecha del recibo)"
            )
        )
    )

    private val movementParserModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = movementDraftSchema
            }
        )
    }

    // 3. Esquema JSON y modelo para resumen semanal/mensual y consejo corto
    private val reportSchema = Schema.obj(
        properties = mapOf(
            "tituloPeriodo" to Schema.string(description = "Título corto del análisis"),
            "queMejoro" to Schema.string(description = "Qué mejoró en el período con cifras reales en COP"),
            "queEmpeoro" to Schema.string(description = "Qué empeoró o qué categoría subió más con cifras reales en COP"),
            "recomendaciones" to Schema.array(
                items = Schema.string(),
                description = "Exactamente 3 recomendaciones concretas en español colombiano con cifras reales"
            ),
            "consejoCortoHoy" to Schema.string(description = "Un consejo directo de máximo 160 caracteres para la pantalla de Inicio")
        )
    )

    private val reportModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = reportSchema
            }
        )
    }

    // 4. Esquema JSON para categorización automática según la nota
    private val categorySuggestionSchema = Schema.obj(
        properties = mapOf(
            "categoria" to Schema.string(description = "Nombre exacto de la categoría sugerida")
        )
    )

    private val categoryModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = categorySuggestionSchema
            }
        )
    }

    /**
     * Convierte una frase dictada o escrita (ej. "gasté 25 mil en almuerzo con Nequi") en un borrador
     * de movimiento listo para confirmar.
     */
    suspend fun parseNaturalLanguageMovement(
        userText: String,
        availableCategories: List<String>,
        availableAccounts: List<String>
    ): Result<ParsedMovementDraft> {
        return try {
            val prompt = """
                Eres el motor financiero de MiPlata en Colombia.
                Convierte el siguiente texto o dictado del usuario en un movimiento en Pesos Colombianos (COP).
                Reglas de conversión:
                - "mil" o "lucas" = multiplicar por 1000 (ej: "25 mil" = 25000).
                - "barra" o "palo" o "millón" = 1000000.
                - Elige la categoría únicamente entre: ${availableCategories.joinToString(", ")}.
                - Elige la cuenta únicamente entre: ${availableAccounts.joinToString(", ")}. Si no menciona ninguna, usa la primera o Nequi.
                Texto del usuario: "$userText"
            """.trimIndent()

            val response = withTimeout(45_000L) {
                movementParserModel.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Respuesta vacía de Gemini.")
            val json = JSONObject(rawJson)

            val tipoStr = json.optString("tipo", "GASTO")
            val type = runCatching { TransactionType.valueOf(tipoStr) }.getOrDefault(TransactionType.GASTO)
            val amount = json.optLong("montoCop", 0L)
            val cat = json.optString("categoria", "Otros")
            val acc = json.optString("cuenta", availableAccounts.firstOrNull() ?: "Nequi")
            val note = json.optString("nota", userText)
            val dateDesc = json.optString("fechaTexto", "Hoy")

            Result.success(
                ParsedMovementDraft(
                    type = type,
                    amount = amount,
                    categoryName = cat,
                    accountName = acc,
                    note = note,
                    dateDescription = dateDesc
                )
            )
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.e(AI_SERVICE_TAG, "Timeout al analizar texto con Gemini", e)
                Result.failure(Exception("Tiempo de espera agotado al conectar con Gemini. Verifica tu conexión a internet."))
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.e(AI_SERVICE_TAG, "Error al analizar texto con Gemini", e)
            Result.failure(Exception("No se pudo conectar con Gemini (${e.localizedMessage ?: "sin internet"}). Puedes registrar el movimiento manualmente."))
        }
    }

    /**
     * Extrae monto, comercio, fecha y categoría desde la foto de un recibo o factura en Colombia.
     */
    suspend fun parseReceiptPhoto(
        bitmap: Bitmap,
        availableCategories: List<String>,
        availableAccounts: List<String>
    ): Result<ParsedMovementDraft> {
        return try {
            val prompt = """
                Analiza esta foto de un recibo, tirilla o factura de compra en Colombia.
                Extrae:
                - El monto total pagado en pesos colombianos (COP, entero sin decimales).
                - El nombre del comercio o descripción corta en 'nota'.
                - La fecha del recibo en 'fechaTexto'.
                - Sugiere la categoría más apropiada de esta lista: ${availableCategories.joinToString(", ")}.
                - Si en el recibo aparece el medio de pago (Efectivo, Nequi, Nu, Tarjeta), indícalo en 'cuenta' usando una de: ${availableAccounts.joinToString(", ")}.
            """.trimIndent()

            val response = withTimeout(60_000L) {
                movementParserModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt)
                    }
                )
            }
            val rawJson = response.text ?: throw IllegalStateException("No se pudo leer el recibo.")
            val json = JSONObject(rawJson)

            Result.success(
                ParsedMovementDraft(
                    type = TransactionType.GASTO,
                    amount = json.optLong("montoCop", 0L),
                    categoryName = json.optString("categoria", "Mercado"),
                    accountName = json.optString("cuenta", availableAccounts.firstOrNull() ?: "Efectivo"),
                    note = json.optString("nota", "Recibo escaneado"),
                    dateDescription = json.optString("fechaTexto", "Hoy")
                )
            )
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.e(AI_SERVICE_TAG, "Timeout al analizar foto de recibo", e)
                Result.failure(Exception("La lectura del recibo tardó demasiado. Verifica tu conexión e inténtalo de nuevo."))
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.e(AI_SERVICE_TAG, "Error al leer foto de recibo con Gemini", e)
            Result.failure(Exception("No se pudo analizar la foto con Gemini (${e.localizedMessage ?: "sin internet"})."))
        }
    }

    /**
     * Sugiere automáticamente la categoría de un movimiento según la nota escrita por el usuario.
     */
    suspend fun suggestCategoryFromNote(
        note: String,
        availableCategories: List<String>
    ): Result<String> {
        return try {
            val prompt = """
                Según la nota de un movimiento financiero en Colombia: "$note",
                elige la categoría exacta más adecuada entre: ${availableCategories.joinToString(", ")}.
            """.trimIndent()
            val response = withTimeout(30_000L) {
                categoryModel.generateContent(prompt)
            }
            val json = JSONObject(response.text ?: "{}")
            Result.success(json.optString("categoria", ""))
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) Result.failure(e) else throw e
        } catch (e: Exception) {
            Log.e(AI_SERVICE_TAG, "Error sugiriendo categoría", e)
            Result.failure(e)
        }
    }

    /**
     * Genera el resumen semanal/mensual en lenguaje sencillo (qué mejoró, qué empeoró y 3 recomendaciones
     * con cifras reales) además del consejo corto para la pantalla de Inicio.
     */
    suspend fun generateStructuredReport(aggregatedContext: String): Result<AiFinancialReport> {
        return try {
            val prompt = """
                Eres el asesor financiero personal de MiPlata en Colombia.
                Tono: directo, cercano, en español colombiano, cero regaños, siempre usando las cifras reales en COP ($1.250.000).
                Regla estricta: NUNCA inventes cifras; usa exclusivamente los datos del resumen agregado a continuación.
                
                RESUMEN AGREGADO DEL USUARIO:
                $aggregatedContext
            """.trimIndent()

            val response = withTimeout(60_000L) {
                reportModel.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Respuesta vacía")
            val json = JSONObject(rawJson)
            val recsArray = json.optJSONArray("recomendaciones")
            val recs = mutableListOf<String>()
            if (recsArray != null) {
                for (i in 0 until recsArray.length()) {
                    recs += recsArray.getString(i)
                }
            }

            Result.success(
                AiFinancialReport(
                    periodTitle = json.optString("tituloPeriodo", "Balance Inteligente del Período"),
                    whatImproved = json.optString("queMejoro", "Controlaste tus gastos frente al límite disponible."),
                    whatWorsened = json.optString("queEmpeoro", "Revisa las categorías con mayor consumo acumulado."),
                    recommendations = recs.ifEmpty {
                        listOf("Mantén tus gastos diarios por debajo de tu disponible por día.")
                    },
                    shortDailyTip = json.optString("consejoCortoHoy", "Cuida tu disponible diario para cumplir tus metas de ahorro.")
                )
            )
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.e(AI_SERVICE_TAG, "Timeout generando reporte IA", e)
                Result.failure(Exception("Tiempo de espera agotado al generar el análisis con Gemini."))
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.e(AI_SERVICE_TAG, "Error generando reporte IA", e)
            Result.failure(Exception("Sin conexión a Gemini (${e.localizedMessage ?: "offline"}). La app sigue funcionando 100% local."))
        }
    }

    /**
     * Chat financiero en streaming. Envía solo el resumen agregado (nunca la base de datos completa).
     */
    fun streamFinancialChatAnswer(
        question: String,
        aggregatedContext: String
    ): Flow<String> {
        val prompt = """
            Eres el asistente financiero de la app MiPlata en Colombia.
            Reglas obligatorias:
            1. Habla directo, claro, en español de Colombia, sin regaños.
            2. Usa SIEMPRE cifras reales en formato COP con punto de miles (ej. $300.000) tomadas del resumen agregado.
            3. NUNCA inventes cifras ni supongas movimientos que no estén en el resumen. Si no tienes un dato específico, dilo claramente.
            4. Sé conciso (máximo 2 o 3 párrafos cortos o viñetas concretas).
            
            RESUMEN AGREGADO ACTUAL DEL USUARIO:
            $aggregatedContext
            
            PREGUNTA DEL USUARIO:
            "$question"
        """.trimIndent()

        return chatModel.generateContentStream(prompt).map { chunk ->
            chunk.text.orEmpty()
        }
    }
}
