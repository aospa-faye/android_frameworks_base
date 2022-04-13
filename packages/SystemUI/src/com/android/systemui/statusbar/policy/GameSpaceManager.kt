/*
 * Copyright (C) 2021 Chaldeaprjkt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy

import android.app.ActivityTaskManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.internal.app.IGameSpaceCallback
import com.android.internal.app.IGameSpaceService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners

import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

@SysUISingleton
class GameSpaceManager @Inject constructor(
    private val context: Context,
    private val keyguardStateController: KeyguardStateController,
) {
    private val handler by lazy { GameSpaceHandler(Looper.getMainLooper()) }
    private val taskManager by lazy { ActivityTaskManager.getService() }

    private var activeGame: String? = null
    private var isRegistered = false

    private val callbacks = CopyOnWriteArrayList<IGameSpaceCallback>()

    private val binder = object : IGameSpaceService.Stub() {
        override fun registerCallback(callback: IGameSpaceCallback?) {
            if (callback == null || callbacks.contains(callback)) return
            callbacks.add(callback)
            try {
                callback.asBinder().linkToDeath({
                    callbacks.remove(callback)
                }, 0)
            } catch (e: RemoteException) {
                callbacks.remove(callback)
            }
        }

        override fun unregisterCallback(callback: IGameSpaceCallback?) {
            if (callback != null) {
                callbacks.remove(callback)
            }
        }
    }

    private val taskStackChangeListener = object : TaskStackChangeListener {
        override fun onTaskStackChanged() {
            handler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP)
        }
    }

    private val interactivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    activeGame = null
                    handler.sendEmptyMessage(MSG_DISPATCH_FOREGROUND_APP)
                }
            }
        }
    }

    private val keyguardStateCallback = object : KeyguardStateController.Callback {
        override fun onKeyguardShowingChanged() {
            if (keyguardStateController.isShowing) return
            handler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP)
        }
    }

    private inner class GameSpaceHandler(looper: Looper) : Handler(looper, null, true) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_UPDATE_FOREGROUND_APP -> checkForegroundApp()
                MSG_DISPATCH_FOREGROUND_APP -> dispatchForegroundApp()
            }
        }
    }

    private fun checkForegroundApp() {
        try {
            val info = taskManager.focusedRootTaskInfo
            info?.topActivity ?: return
            val packageName = info.topActivity?.packageName
            activeGame = checkGameList(packageName)
            handler.sendEmptyMessage(MSG_DISPATCH_FOREGROUND_APP)
        } catch (e: RemoteException) {
            Log.e(TAG, "Failed to check foreground app", e)
        }
    }

    private fun dispatchForegroundApp() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive && activeGame != null) return
        val current = activeGame
        val action = if (current != null) ACTION_GAME_START else ACTION_GAME_STOP

        // Broadcast to GameBroadcastReceiver
        Intent(action).apply {
            setPackage(GAMESPACE_PACKAGE)
            component = ComponentName.unflattenFromString(RECEIVER_CLASS)
            putExtra(EXTRA_CALLER_NAME, context.packageName)
            if (current != null) putExtra(EXTRA_ACTIVE_GAME, current)
            addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING
                or Intent.FLAG_RECEIVER_FOREGROUND
                or Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND)
            context.sendBroadcastAsUser(this, UserHandle.SYSTEM,
                android.Manifest.permission.MANAGE_GAME_MODE)
        }

        // Notify registered AIDL callbacks
        for (callback in callbacks) {
            try {
                if (current != null) {
                    callback.onGameStart(current)
                } else {
                    callback.onGameLeave()
                }
            } catch (e: Exception) {
                callbacks.remove(callback)
            }
        }
    }

    fun observe() {
        val taskStackChangeListeners = TaskStackChangeListeners.getInstance()
        if (isRegistered) {
            taskStackChangeListeners.unregisterTaskStackListener(taskStackChangeListener)
        }
        taskStackChangeListeners.registerTaskStackListener(taskStackChangeListener)
        isRegistered = true
        handler.sendEmptyMessage(MSG_UPDATE_FOREGROUND_APP)
        context.registerReceiver(interactivityReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        }, Context.RECEIVER_EXPORTED)
        keyguardStateController.addCallback(keyguardStateCallback)

        try {
            ServiceManager.addService("game_space", binder)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register game_space service", e)
        }
    }

    fun isGameActive() = activeGame != null

    fun shouldSuppressFullScreenIntent() =
        Settings.System.getInt(context.contentResolver,
            Settings.System.GAMESPACE_SUPPRESS_FULLSCREEN_INTENT, 0) == 1 && isGameActive()

    private fun checkGameList(packageName: String?): String? {
        packageName ?: return null
        val games = Settings.System.getString(context.contentResolver, Settings.System.GAMESPACE_GAME_LIST)
        if (games.isNullOrEmpty())
            return null

        return games.split(";")
            .map { it.split("=").first() }
            .firstOrNull { it == packageName }
    }

    init {
        instance = this
    }

    companion object {
        private const val TAG = "GameSpaceManager"
        private const val ACTION_GAME_START = "io.chaldeaprjkt.gamespace.action.GAME_START"
        private const val ACTION_GAME_STOP = "io.chaldeaprjkt.gamespace.action.GAME_STOP"
        private const val GAMESPACE_PACKAGE = "io.chaldeaprjkt.gamespace"
        private const val RECEIVER_CLASS = "io.chaldeaprjkt.gamespace/.gamebar.GameBroadcastReceiver"
        private const val EXTRA_CALLER_NAME = "source"
        private const val EXTRA_ACTIVE_GAME = "package_name"

        private const val MSG_UPDATE_FOREGROUND_APP = 0
        private const val MSG_DISPATCH_FOREGROUND_APP = 1

        private var instance: GameSpaceManager? = null

        @JvmStatic
        fun getInstance(): GameSpaceManager? = instance
    }
}
