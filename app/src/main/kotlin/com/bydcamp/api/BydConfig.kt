package com.bydcamp.api

data class BydConfig(
    val baseURL: String,
    val countryCode: String,
    val language: String,
    val timeZone: String
) {
    companion object {
        fun fromRegion(region: String): BydConfig {
            val r = region.uppercase().trim()
            val baseURL: String
            var countryCode = r
            var language = "en"
            var timeZone = "UTC"

            when (r) {
                "KR" -> { baseURL = "https://dilinkappoversea-kr-ali.byd.auto"; language = "ko"; timeZone = "Asia/Seoul" }
                "EU" -> { baseURL = "https://dilinkappoversea-eu.byd.auto"; countryCode = "GB"; language = "en"; timeZone = "Europe/London" }
                "JP" -> { baseURL = "https://dilinkappoversea-jp.byd.auto"; language = "ja"; timeZone = "Asia/Tokyo" }
                "SG" -> { baseURL = "https://dilinkappoversea-sg.byd.auto"; language = "en"; timeZone = "Asia/Singapore" }
                "AU" -> { baseURL = "https://dilinkappoversea-au.byd.auto"; language = "en"; timeZone = "Australia/Sydney" }
                "BR" -> { baseURL = "https://dilinkappoversea-br.byd.auto"; language = "pt"; timeZone = "America/Sao_Paulo" }
                "MX" -> { baseURL = "https://dilinkappoversea-mx.byd.auto"; language = "es"; timeZone = "America/Mexico_City" }
                "NO" -> { baseURL = "https://dilinkappoversea-no.byd.auto"; language = "no"; timeZone = "Europe/Oslo" }
                "UZ" -> { baseURL = "https://dilinkappoversea-uz.byd.auto"; language = "en"; timeZone = "Asia/Tashkent" }
                "KZ" -> { baseURL = "https://dilinkappoversea-kz.byd.auto"; language = "en"; timeZone = "Asia/Almaty" }
                "IN" -> { baseURL = "https://dilinkappoversea-in.byd.auto"; language = "en"; timeZone = "Asia/Kolkata" }
                "ID" -> { baseURL = "https://dilinkappoversea-id.byd.auto"; language = "in"; timeZone = "Asia/Jakarta" }
                "VN" -> { baseURL = "https://dilinkappoversea-vn.byd.auto"; language = "vi"; timeZone = "Asia/Ho_Chi_Minh" }
                "SA" -> { baseURL = "https://dilinkappoversea-sa.byd.auto"; language = "ar"; timeZone = "Asia/Riyadh" }
                "OM" -> { baseURL = "https://dilinkappoversea-om.byd.auto"; language = "ar"; timeZone = "Asia/Muscat" }
                else -> { baseURL = "https://dilinkappoversea-${r.lowercase()}.byd.auto" }
            }

            return BydConfig(baseURL = baseURL, countryCode = countryCode, language = language, timeZone = timeZone)
        }
    }
}
