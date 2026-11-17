package com.example.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Utilidad para formatear cifras en Pesos Colombianos (COP) con punto de miles y sin decimales:
 * Ejemplo: 1250000L -> "$1.250.000", -45000L -> "-$45.000"
 */
object CopFormatter {

    private val symbols = DecimalFormatSymbols(Locale("es", "CO")).apply {
        groupingSeparator = '.'
        decimalSeparator = ','
    }

    private val formatter = DecimalFormat("#,###", symbols)

    /**
     * Formatea un monto en COP. Si [hideBalance] es true, devuelve "$ ••••••".
     */
    fun format(amount: Long, hideBalance: Boolean = false, showPlusSign: Boolean = false): String {
        if (hideBalance) return "$ ••••••"
        val absValue = abs(amount)
        val formattedNumber = synchronized(formatter) {
            formatter.format(absValue)
        }
        return when {
            amount < 0 -> "-$$formattedNumber"
            showPlusSign && amount > 0 -> "+$$formattedNumber"
            else -> "$$formattedNumber"
        }
    }

    /**
     * Versión compacta para ejes de gráficos (ej. $1.2M o $450k).
     */
    fun formatCompact(amount: Long, hideBalance: Boolean = false): String {
        if (hideBalance) return "$•••"
        val absVal = abs(amount)
        val sign = if (amount < 0) "-" else ""
        return when {
            absVal >= 1_000_000L -> {
                val millions = absVal / 1_000_000.0
                val rounded = (millions * 10).toLong() / 10.0
                if (rounded % 1.0 == 0.0) "$sign$${rounded.toLong()}M" else "$sign$${rounded}M"
            }
            absVal >= 1_000L -> "$sign$${absVal / 1_000L}k"
            else -> "$sign$$absVal"
        }
    }

    /**
     * Convierte texto con dígitos o palabras comunes ("25 mil", "1.250.000") a Long.
     */
    fun parseDigitsToLong(raw: String): Long {
        val digits = raw.filter { it.isDigit() }
        return digits.toLongOrNull() ?: 0L
    }

    /**
     * Formatea fecha amigable en español (Colombia): "Hoy, 9 nov", "Ayer, 8 nov" o "Lun, 4 nov 2026".
     */
    fun formatDayHeader(timestamp: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val calTarget = Calendar.getInstance().apply { timeInMillis = timestamp }
        val calNow = Calendar.getInstance().apply { timeInMillis = nowMillis }

        val sameYear = calTarget.get(Calendar.YEAR) == calNow.get(Calendar.YEAR)
        val sameDay = sameYear && calTarget.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)

        val calYesterday = (calNow.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = calTarget.get(Calendar.YEAR) == calYesterday.get(Calendar.YEAR) &&
            calTarget.get(Calendar.DAY_OF_YEAR) == calYesterday.get(Calendar.DAY_OF_YEAR)

        val esLocale = Locale("es", "CO")
        val shortDate = SimpleDateFormat("d 'de' MMM", esLocale).format(Date(timestamp))
        return when {
            sameDay -> "Hoy, $shortDate"
            isYesterday -> "Ayer, $shortDate"
            else -> SimpleDateFormat("EEE, d 'de' MMM", esLocale)
                .format(Date(timestamp))
                .replaceFirstChar { it.uppercase() }
        }
    }

    fun formatTime(timestamp: Long): String {
        val esLocale = Locale("es", "CO")
        return SimpleDateFormat("h:mm a", esLocale).format(Date(timestamp))
    }

    fun formatShortDate(timestamp: Long): String {
        val esLocale = Locale("es", "CO")
        return SimpleDateFormat("dd/MM/yyyy", esLocale).format(Date(timestamp))
    }

    fun currentYearMonthKey(timestamp: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        return "%04d-%02d".format(year, month)
    }
}
