/*
 * Copyright (C) 2019. Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package catchup.app

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.os.strictmode.DiskReadViolation
import android.os.strictmode.UntaggedSocketViolation
import android.util.Log
import catchup.app.ApplicationModule.Initializers
import catchup.app.data.LumberYard
import catchup.app.data.tree
import catchup.appconfig.AppConfig
import catchup.base.ui.CatchUpObjectWatcher
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.annotation.AnnotationRetention.BINARY
import leakcanary.AppWatcher
import leakcanary.LeakCanary
import shark.AndroidReferenceMatchers
import timber.log.Timber

@ContributesTo(AppScope::class)
interface DebugApplicationModule {

  @Qualifier @Retention(BINARY) private annotation class LeakCanaryEnabled

  /**
   * Disabled on API 28 because there's a pretty vicious memory leak that constantly triggers
   * https://github.com/square/leakcanary/issues/1081
   */
  @LeakCanaryEnabled
  @Provides
  fun provideLeakCanaryEnabled(appConfig: AppConfig): Boolean = appConfig.sdkInt != 28

  @Provides
  fun provideObjectWatcher(@LeakCanaryEnabled leakCanaryEnabled: Boolean): CatchUpObjectWatcher {
    return if (leakCanaryEnabled) {
      object : CatchUpObjectWatcher {
        override fun watch(watchedReference: Any) {
          AppWatcher.objectWatcher.expectWeaklyReachable(watchedReference, "Uhhh because reasons")
        }
      }
    } else {
      CatchUpObjectWatcher.None
    }
  }

  @Provides
  fun provideLeakCanaryConfig(@LeakCanaryEnabled leakCanaryEnabled: Boolean): LeakCanary.Config {
    return if (leakCanaryEnabled) {
      LeakCanary.config.copy(referenceMatchers = AndroidReferenceMatchers.appDefaults)
    } else {
      LeakCanary.config
    }
  }

  @Initializers
  @IntoSet
  @Provides
  fun leakCanaryInit(
    application: Application,
    objectWatcher: CatchUpObjectWatcher,
    leakCanaryConfig: LeakCanary.Config,
  ): () -> Unit = {
    LeakCanary.config = leakCanaryConfig

    application.registerActivityLifecycleCallbacks(
      object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {}

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
          //        if (activity is MainActivity) {
          //          // Ignore Chuck
          //          return
          //        }
          objectWatcher.watch(activity)
        }
      }
    )
  }

  @Qualifier @Retention(BINARY) private annotation class StrictModeExecutor

  @StrictModeExecutor
  @Provides
  fun strictModeExecutor(): ExecutorService = Executors.newSingleThreadExecutor()

  @Initializers
  @IntoSet
  @Provides
  fun strictModeInit(
    @StrictModeExecutor penaltyListenerExecutor: Lazy<ExecutorService>
  ): () -> Unit = {
    StrictMode.setThreadPolicy(
      StrictMode.ThreadPolicy.Builder()
        .detectAll()
        .penaltyListener(penaltyListenerExecutor.value) { Timber.w(it) }
        .build()
    )
    StrictMode.setVmPolicy(
      VmPolicy.Builder()
        .detectAll()
        .penaltyLog()
        .penaltyListener(
          penaltyListenerExecutor.value,
          StrictMode.OnVmViolationListener {
            when (it) {
              is UntaggedSocketViolation -> {
                // Firebase and OkHttp don't tag sockets
                return@OnVmViolationListener
              }
              is DiskReadViolation -> {
                if (it.stackTrace.any { it.methodName == "onCreatePreferences" }) {
                  // PreferenceFragment hits preferences directly
                  return@OnVmViolationListener
                }
              }
            }
            // Note: Chuck causes a closeable leak. Possible
            // https://github.com/square/okhttp/issues/3174
            Timber.w(it)
          },
        )
        .build()
    )
  }

  @IntoSet @Provides fun provideDebugTree(): Timber.Tree = Timber.DebugTree()

  @IntoSet
  @Provides
  fun provideLumberYardTree(lumberYard: LumberYard): Timber.Tree = lumberYard.tree()

  @IntoSet
  @Provides
  fun provideCrashOnErrorTree(): Timber.Tree {
    return object : Timber.Tree() {
      override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.ERROR) {
          throw RuntimeException("Timber e! Please fix:\nTag=$tag\nMessage=$message", t)
        }
      }
    }
  }
}
