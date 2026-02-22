package com.example.accessibilityguard

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Owner として登録される DeviceAdminReceiver。
 * ADB で一度だけ set-device-owner を実行して有効化する。
 */
class GuardAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "GuardAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, GuardAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin disabled")
    }
}
