package takagi.ru.monica.ime

/** Shared ten-column grid. Insets in the home row never stretch its nine letter keys. */
internal data class ImeKeyGeometry(val width: Float, val gap: Float = 4f, val pixel: Float = 0f) {
    // Round down once for the whole row, so Compose never squeezes its final key.
    private fun fit(value: Float) = if (pixel > 0f) kotlin.math.floor(value / pixel + 0.0001f) * pixel else value
    val letter = fit((width - gap * 9) / 10)
    val homeInset = (letter + gap) / 2
    val action = fit((width - letter * 7 - gap * 8) / 2)
    val space = width - action * 2 - letter * 2 - gap * 4
    val number = fit((width - gap * 3) / 4)
}
