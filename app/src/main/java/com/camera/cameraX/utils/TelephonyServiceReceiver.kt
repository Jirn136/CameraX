/*
 * @category ContusFly
 * @copyright Copyright (C) 2016 Contus. All rights reserved.
 * @license http://www.apache.org/licenses/LICENSE-2.0
 */
package com.camera.cameraX.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager


/**
 * Telephony broadcast receiver
 *
 * @author ContusTeam <developers></developers>@contus.in>
 * @version 2.0
 */
class TelephonyServiceReceiver : BroadcastReceiver() {
    /**
     * This method will be called when a telephonic call received
     *
     * @param context received startupActivityContext
     * @param intent  received intent
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != null && intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {

            // State has changed
            val phoneState =
                if (intent.hasExtra(TelephonyManager.EXTRA_STATE)) intent.getStringExtra(
                    TelephonyManager.EXTRA_STATE
                ) else null
            sendBroadcast(context, phoneState)
        }
    }

    /**
     * Send local broadcast for callState changes
     *
     * @param callState it indicates [TelephonyManager] call states.
     */
    private fun sendBroadcast(context: Context, callState: String?) {
        Log.d("telephonyCallTest", "BroadCastingCallState: $callState")
        val intent = Intent(ACTION_PHONE_CALL_STATE_CHANGED)
        intent.putExtra(CALL_STATE, callState)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    companion object {
        /**
         * key Constant for local broadcast intent
         */
        const val CALL_STATE = "callState"
        const val ACTION_PHONE_CALL_STATE_CHANGED = "call.action.PHONE_CALL_STATE_CHANGED"
    }
}