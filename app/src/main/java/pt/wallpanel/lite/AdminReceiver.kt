package pt.wallpanel.lite

import android.app.admin.DeviceAdminReceiver

/** Necessário apenas para poder chamar DevicePolicyManager.lockNow() (desliga o ecrã a sério). */
class AdminReceiver : DeviceAdminReceiver()
