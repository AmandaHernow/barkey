// Copyright 2026 Amanda Hernow. Licensed under PolyForm Noncommercial 1.0.0.
package net.hernow.barkey

import android.content.Context
import android.content.Intent
import java.security.MessageDigest

/** Stores the local terms version the user accepted before using BarKey. */
internal object PersonalUseLicense {
    const val ACTION_ACCEPTED = "net.hernow.barkey.action.PERSONAL_USE_LICENSE_ACCEPTED"

    private const val PREFERENCES_NAME = "personal_use_terms"
    private const val ACCEPTED_VERSION_KEY = "accepted_terms_revision"

    /** Requires agreement again whenever the displayed terms or bundled licence changes. */
    fun hasAccepted(context: Context): Boolean =
        preferences(context).getString(ACCEPTED_VERSION_KEY, null) == termsRevision(context)

    /** Saves agreement locally before the setup guide or keyboard becomes available. */
    fun accept(context: Context): Boolean = preferences(context).edit()
        .putString(ACCEPTED_VERSION_KEY, termsRevision(context))
        .commit()

    /** Reads the bundled licence so first run works without an internet connection. */
    fun fullTerms(context: Context): String = context.resources
        .openRawResource(R.raw.polyform_noncommercial_license)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
        .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace("**", "")
        .replace("`", "")

    /** Lets an already-running IME refresh its view after agreement. */
    fun notifyAccepted(context: Context) {
        context.sendBroadcast(Intent(ACTION_ACCEPTED).setPackage(context.packageName))
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Detects wording changes without sending the licence text or acceptance off the device. */
    private fun termsRevision(context: Context): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            R.string.license_agreement_eyebrow,
            R.string.license_agreement_title,
            R.string.license_agreement_intro,
            R.string.license_personal_use_title,
            R.string.license_agreement_summary,
            R.string.license_company_title,
            R.string.license_company_contact,
            R.string.license_source_terms_title,
            R.string.license_agreement_consent,
            R.string.license_agreement_agree,
            R.string.license_agreement_decline,
        ).forEach { resourceId ->
            digest.update(context.getString(resourceId).toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        context.resources.openRawResource(R.raw.polyform_noncommercial_license).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
