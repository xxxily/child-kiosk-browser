package site.anzz.childkiosk

internal fun shouldRetainProtectedNativeLocationRequestsOnStop(
    hasProtectedSession: Boolean,
    isFinishing: Boolean,
    isChangingConfigurations: Boolean
): Boolean = hasProtectedSession && !isFinishing && !isChangingConfigurations
