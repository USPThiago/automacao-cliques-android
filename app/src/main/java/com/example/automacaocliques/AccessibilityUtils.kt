package com.example.automacaocliques

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils

object AccessibilityUtils {

    /**
     * Le em Settings.Secure se o servico do app esta na lista de servicos de
     * acessibilidade habilitados pelo usuario.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ClickAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (TextUtils.isEmpty(enabled)) return false

        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry)?.let {
                it.packageName == expected.packageName && it.className == expected.className
            } == true
        }
    }
}
