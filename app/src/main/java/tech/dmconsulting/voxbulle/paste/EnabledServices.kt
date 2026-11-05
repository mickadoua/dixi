package tech.dmconsulting.voxbulle.paste

/**
 * Whether [className] of [packageName] is in [enabledServices], the value of
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`: components separated by `:`,
 * each `package/full.Class` or `package/.Class`.
 */
fun isServiceListed(enabledServices: String?, packageName: String, className: String): Boolean =
    enabledServices.orEmpty().split(':').any { entry ->
        val pkg = entry.substringBefore('/', missingDelimiterValue = "")
        val cls = entry.substringAfter('/', missingDelimiterValue = "")
        pkg.equals(packageName, ignoreCase = true) &&
            (cls == className || (cls.startsWith('.') && packageName + cls == className))
    }
