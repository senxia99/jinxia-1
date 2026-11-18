package com.senxia.adskip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机广播：无障碍服务由系统按开关自动绑定，这里不做重复拉起。
 * 仅提示用户服务可能需要重新确认（HyperOS 大版本更新后偶尔会重置无障碍开关）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Log.i("AdSkip", "开机完成，无障碍服务由系统按设置自动绑定")
        }
    }
}
