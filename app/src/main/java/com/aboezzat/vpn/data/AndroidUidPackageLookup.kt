package com.aboezzat.vpn.data

import android.content.pm.PackageManager
import com.aboezzat.vpn.core.UidPackageLookup

/**
 * Android PackageManager adapter used after a connection UID is resolved.
 * Returns an empty list when Android cannot associate the UID with visible
 * packages; callers must treat that as unknown rather than guess.
 */
class AndroidUidPackageLookup(
    private val packageManager: PackageManager
) : UidPackageLookup {
    override fun packagesForUid(uid: Int): List<String> {
        if (uid <= 0) return emptyList()
        return packageManager.getPackagesForUid(uid)
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.sorted()
            ?.toList()
            .orEmpty()
    }
}
