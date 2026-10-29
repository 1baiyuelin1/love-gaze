package app.champion

import java.util.Locale

object Places {
    private val countries = setOf("中国", "中华人民共和国", "中国大陆", "china", "cn", "people's republic of china")
    fun specific(value: String, country: String = ""): String {
        val trimmed = value.trim().trim(',', '，', ' ')
        return trimmed.takeUnless { it.isBlank() || it.lowercase(Locale.ROOT) in countries || it == country.trim() }.orEmpty()
    }

    fun address(line: String, country: String, parts: List<String>): String {
        val details = parts.map { specific(it, country) }.filter { it.isNotBlank() }.distinct()
        val assembled = details.fold("") { text, part -> if (text.contains(part)) text else text + part }
        return listOf(specific(line, country), assembled).maxByOrNull { it.length }.orEmpty().take(160)
    }

    fun coordinates(latitude: Double, longitude: Double) = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

    fun display(place: String, latitude: Double? = null, longitude: Double? = null): String =
        specific(place).ifBlank {
            if (latitude != null && longitude != null) "坐标 ${coordinates(latitude, longitude)}" else "等待详细定位"
        }
}
