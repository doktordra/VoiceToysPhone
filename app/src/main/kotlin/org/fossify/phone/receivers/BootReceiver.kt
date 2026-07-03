package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.phone.extensions.config
import org.fossify.phone.services.KeepAliveService

// restarts the keep-alive service after the device reboots, if the user enabled it
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (context.config.keepAlive) {
            KeepAliveService.start(context)
        }
    }
}
