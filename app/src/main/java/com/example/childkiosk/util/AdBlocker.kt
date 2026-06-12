package com.example.childkiosk.util

object AdBlocker {

    private val AD_HOST_KEYWORDS = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adsystem.com",
        "adservice.google",
        "adnxs.com",
        "advertising.com",
        "adcolony.com",
        "applovin.com",
        "facebook.com/tr",
        "fbcdn.net/ads",
        "scorecardresearch.com",
        "criteo.com",
        "criteo.net",
        "moatads.com",
        "outbrain.com",
        "taboola.com",
        "yieldmo.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "adsafeprotected.com",
        "amazon-adsystem.com",
        "iqiyi.com/ads",
        "ad.youku.com",
        "atm.youku.com",
        "miaozhen.com",
        "umeng.com",
        "umtrack.com",
        "cnzz.com",
        "alimama.com",
        "tanx.com",
        "mmstat.com",
        "track.uc.cn",
        "growingio.com",
        "sensorsdata.cn",
        "appsflyer.com",
        "kochava.com",
        "branch.io",
        "adjust.com",
        "mixpanel.com",
        "hotjar.com",
        "segment.com",
        "amplitude.com"
    )

    fun isAdHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val lower = host.lowercase()
        return AD_HOST_KEYWORDS.any { keyword ->
            lower == keyword || lower.endsWith(".$keyword") || lower.contains(keyword)
        }
    }

    /**
     * 判断完整 URL 是否命中广告/统计黑名单。
     * 同时检查 host 和 path 中是否包含黑名单关键字（部分广告 SDK 通过统一 CDN 分发）。
     */
    fun isAdRequest(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        return AD_HOST_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }
}
