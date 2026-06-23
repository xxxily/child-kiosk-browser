package site.anzz.childkiosk.util.filter

object FilterCatalog {

    private const val LOCAL_SUPPLEMENTAL_RULES = """
! Child Kiosk local supplemental rules
||doubleclick.net^
||googlesyndication.com^
||googleadservices.com^
||adservice.google^
||googletagservices.com^
||amazon-adsystem.com^
||taboola.com^
||outbrain.com^
||criteo.com^
||criteo.net^
||pubmatic.com^
||rubiconproject.com^
||openx.net^
||adnxs.com^
||adsafeprotected.com^
||umeng.com^
||cnzz.com^
||mmstat.com^
||tanx.com^
||alimama.com^
||miaozhen.com^
||track.uc.cn^
||appsflyer.com^
||adjust.com^
||branch.io^
||hotjar.com^
||scorecardresearch.com^
facebook.com/tr${'$'}script,image,third-party
fbcdn.net/ads${'$'}image,third-party
"""

    private const val EASYLIST_SEED_RULES = """
[Adblock Plus 2.0]
! EasyList compatible bundled starter subset. Full list is available through subscription URL.
||doubleclick.net^
||googlesyndication.com^
||googleadservices.com^
||googletagservices.com^
||adservice.google.com^
||adnxs.com^
||adsystem.com^
||advertising.com^
||adsafeprotected.com^
||amazon-adsystem.com^
||outbrain.com^
||taboola.com^
||pubmatic.com^
||rubiconproject.com^
||openx.net^
/ads/*${'$'}image,script,third-party
/adserver/*${'$'}script,image,third-party
##.ad
##.ads
##.advertisement
##[class*="ad-banner"]
##[id*="ad-banner"]
"""

    private const val EASYPRIVACY_SEED_RULES = """
[Adblock Plus 2.0]
! EasyPrivacy compatible bundled starter subset. Full list is available through subscription URL.
||google-analytics.com^
||googletagmanager.com^
||scorecardresearch.com^
||hotjar.com^
||mixpanel.com^
||segment.com^
||amplitude.com^
||sensorsdata.cn^
||growingio.com^
||umtrack.com^
||umeng.com^
||cnzz.com^
||appsflyer.com^
||adjust.com^
||kochava.com^
||branch.io^
/analytics.js${'$'}script,third-party
/tracking/*${'$'}script,image,third-party
##.tracking-pixel
"""

    private const val ADGUARD_CHINESE_SEED_RULES = """
[Adblock Plus 2.0]
! AdGuard Chinese filter compatible bundled starter subset. Full list is available through subscription URL.
||alimama.com^
||tanx.com^
||mmstat.com^
||miaozhen.com^
||cnzz.com^
||umeng.com^
||umtrack.com^
||track.uc.cn^
||ad.youku.com^
||atm.youku.com^
||iqiyi.com/ads^
/union/ads/${'$'}script,image,third-party
/adplus/${'$'}script,image,third-party
##.ggad
##.ad-wrap
"""

    private const val ADGUARD_MOBILE_SEED_RULES = """
[Adblock Plus 2.0]
! AdGuard Mobile Ads filter compatible bundled starter subset. Full list is available through subscription URL.
||adcolony.com^
||applovin.com^
||unityads.unity3d.com^
||vungle.com^
||ironsrc.com^
||inmobi.com^
||tapjoy.com^
||chartboost.com^
||startappservice.com^
"""

    private const val STRONG_ANNOYANCE_SEED_RULES = """
[Adblock Plus 2.0]
! Strong anti-annoyance starter subset.
||fundingchoicesmessages.google.com^
||cdn.cookielaw.org^${'$'}script,third-party
||privacy-mgmt.com^${'$'}script,third-party
||quantcast.mgr.consensu.org^
*${'$'}removeparam=utm_source|utm_medium|utm_campaign|utm_content|utm_term|fbclid|gclid|yclid
##+js(no-window-open-if)
##.popup-ad
##.modal-ad
##.cookie-banner
"""

    val builtInSubscriptions: List<FilterSubscription> = listOf(
        FilterSubscription(
            id = "easylist",
            title = "EasyList",
            category = "通用广告",
            homepageUrl = "https://easylist.to/",
            subscriptionUrl = "https://easylist.to/easylist/easylist.txt",
            defaultInStandard = true,
            defaultInStrong = true,
            bundledRules = EASYLIST_SEED_RULES
        ),
        FilterSubscription(
            id = "easyprivacy",
            title = "EasyPrivacy",
            category = "通用隐私",
            homepageUrl = "https://easylist.to/",
            subscriptionUrl = "https://easylist.to/easylist/easyprivacy.txt",
            defaultInStandard = true,
            defaultInStrong = true,
            bundledRules = EASYPRIVACY_SEED_RULES
        ),
        FilterSubscription(
            id = "adguard-chinese",
            title = "AdGuard Chinese filter",
            category = "中文广告",
            homepageUrl = "https://adguard.com/kb/general/ad-filtering/adguard-filters/",
            subscriptionUrl = "https://filters.adtidy.org/extension/chromium/filters/224.txt",
            defaultInStandard = true,
            defaultInStrong = true,
            bundledRules = ADGUARD_CHINESE_SEED_RULES
        ),
        FilterSubscription(
            id = "adguard-mobile",
            title = "AdGuard Mobile Ads filter",
            category = "移动广告",
            homepageUrl = "https://adguard.com/kb/general/ad-filtering/adguard-filters/",
            subscriptionUrl = "https://filters.adtidy.org/extension/chromium/filters/11.txt",
            defaultInStandard = true,
            defaultInStrong = true,
            bundledRules = ADGUARD_MOBILE_SEED_RULES
        ),
        FilterSubscription(
            id = "adguard-annoyances-lite",
            title = "AdGuard Annoyances / Popups starter",
            category = "弹窗干扰",
            homepageUrl = "https://adguard.com/kb/general/ad-filtering/adguard-filters/",
            subscriptionUrl = "https://filters.adtidy.org/extension/chromium/filters/14.txt",
            defaultInStandard = false,
            defaultInStrong = true,
            bundledRules = STRONG_ANNOYANCE_SEED_RULES
        ),
        FilterSubscription(
            id = "local-child-supplemental",
            title = "Child Kiosk 本地补充规则",
            category = "儿童安全补充",
            homepageUrl = "local://child-kiosk",
            subscriptionUrl = "local://child-kiosk/local-supplemental.txt",
            defaultInStandard = true,
            defaultInStrong = true,
            bundledRules = LOCAL_SUPPLEMENTAL_RULES
        )
    )

    fun defaultSubscriptionsFor(preset: FilterPreset): List<FilterSubscription> {
        return builtInSubscriptions.map { subscription ->
            val enabled = when (preset) {
                FilterPreset.LIGHT -> subscription.id == "local-child-supplemental"
                FilterPreset.STANDARD_CHILD -> subscription.defaultInStandard
                FilterPreset.STRONG -> subscription.defaultInStrong
                FilterPreset.CUSTOM -> subscription.defaultInStandard
            }
            subscription.copy(enabled = enabled)
        }
    }

    fun subscriptionsForIds(ids: Set<String>): List<FilterSubscription> {
        return builtInSubscriptions.map { it.copy(enabled = it.id in ids) }
    }

    fun customSubscription(
        title: String,
        url: String
    ): FilterSubscription {
        val id = "custom-" + url.hashCode().toUInt().toString(16)
        return FilterSubscription(
            id = id,
            title = title.ifBlank { url.substringAfter("://").substringBefore("/") },
            category = "自定义订阅",
            homepageUrl = url,
            subscriptionUrl = url,
            defaultInStandard = false,
            defaultInStrong = false,
            bundledRules = "",
            enabled = true
        )
    }
}
