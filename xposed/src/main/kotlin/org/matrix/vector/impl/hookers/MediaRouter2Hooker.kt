package org.matrix.vector.impl.hookers

import io.github.libxposed.api.XposedInterface
import org.lsposed.lspd.util.Utils

/**
 * Suppresses the Android 15 strictness crash in MediaRouter2.
 * Fixes Issue #755 where SystemUI crashes due to delayed AudioService initialization.
 */
object MediaRouter2Hooker : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        try {
            return chain.proceed()
        } catch (e: RuntimeException) {
            if (e.message?.contains("currentSystemRoutes") == true) {
                Utils.logW("Suppressed Android 15 MediaRouter2 crash (Issue #755)")
                return null
            }
            throw e
        }
    }
}
