package com.sal7one.transiber.home

import android.content.Context

/** Home presentation preferences only; never provider settings, text or credentials. */
internal object HomeServiceStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("hearth-home", Context.MODE_PRIVATE)
    fun easySetupCollapsed(context: Context): Boolean = prefs(context).getBoolean("easy-setup-collapsed", false)
    fun setEasySetupCollapsed(context: Context, collapsed: Boolean) {
        prefs(context).edit().putBoolean("easy-setup-collapsed", collapsed).apply()
    }
    fun selected(context: Context): HomeService = HomeService.restore(prefs(context).getString("selected-service", null))
    fun remember(context: Context, service: HomeService) {
        val prefs = prefs(context)
        if (prefs.getString("selected-service", null) != service.id) prefs.edit().putString("selected-service", service.id).apply()
    }
}
