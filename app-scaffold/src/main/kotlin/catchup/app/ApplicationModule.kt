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

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.AnimationConstants
import androidx.core.content.getSystemService
import catchup.app.data.DribbbleSizingInterceptor
import catchup.app.data.LumberYard
import catchup.app.data.UnsplashSizingInterceptor
import catchup.appconfig.AppConfig
import catchup.appconfig.AppConfigMetadataContributor
import catchup.base.ui.VersionInfo
import catchup.base.ui.versionInfo
import catchup.di.DataMode
import catchup.di.FakeMode
import catchup.util.injection.qualifiers.ApplicationContext
import catchup.util.kotlin.mapToStateFlow
import coil.Coil
import coil.ComponentRegistry
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.DefaultRequestOptions
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.ImageResult
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import timber.log.Timber

@ContributesTo(AppScope::class)
interface ApplicationModule {

  @Qualifier @Retention(BINARY) annotation class Initializers

  @Qualifier @Retention(BINARY) annotation class AsyncInitializers

  @Qualifier @Retention(BINARY) annotation class LazyDelegate

  /** Provides AppConfig metadata contributors. */
  @Multibinds(allowEmpty = true) fun metadataContributors(): Set<AppConfigMetadataContributor>

  /** Provides initializers for app startup. */
  @Initializers @Multibinds fun initializers(): Set<() -> Unit>

  /** Provides initializers for app startup that can be initialized async. */
  @AsyncInitializers @Multibinds fun asyncInitializers(): Set<() -> Unit>

  @Multibinds fun timberTrees(): Set<Timber.Tree>

  @Binds @ApplicationContext fun provideApplicationContext(real: Application): Context

  @Binds fun bindAppConfig(real: CatchUpAppConfig): AppConfig

  companion object {

    /**
     * This Context is only available for things that don't care what type of Context they need.
     *
     * Wrapped so no one can try to cast it as an Application.
     */
    @Provides
    @SingleIn(AppScope::class)
    internal fun provideGeneralUseContext(@ApplicationContext appContext: Context): Context =
      ContextWrapper(appContext)

    @Provides
    @SingleIn(AppScope::class)
    internal fun versionInfo(@ApplicationContext appContext: Context): VersionInfo =
      appContext.versionInfo

    @AsyncInitializers
    @IntoSet
    @Provides
    fun mainDispatcherInit(): () -> Unit = {
      // This makes a call to disk, so initialize it off the main thread first... ironically
      Dispatchers.Main
    }

    @Initializers
    @IntoSet
    @Provides
    fun coilInit(imageLoader: ImageLoader): () -> Unit = { Coil.setImageLoader(imageLoader) }

    @Qualifier @Retention(BINARY) annotation class IsLowRamDevice

    @IsLowRamDevice
    @Provides
    @SingleIn(AppScope::class)
    fun isLowRam(@ApplicationContext context: Context): Boolean {
      // Prefer higher quality images unless we're on a low RAM device
      return context.getSystemService<ActivityManager>()?.isLowRamDevice != false
    }

    @ExperimentalCoilApi
    @Provides
    @LazyDelegate
    @SingleIn(AppScope::class)
    fun lazyImageLoader(imageLoader: Lazy<ImageLoader>): ImageLoader {
      return object : ImageLoader {
        override val components: ComponentRegistry
          get() = imageLoader.value.components

        override val defaults: DefaultRequestOptions
          get() = imageLoader.value.defaults

        override val diskCache: DiskCache?
          get() = imageLoader.value.diskCache

        override val memoryCache: MemoryCache?
          get() = imageLoader.value.memoryCache

        override fun enqueue(request: ImageRequest): Disposable {
          return imageLoader.value.enqueue(request)
        }

        override suspend fun execute(request: ImageRequest): ImageResult {
          return imageLoader.value.execute(request)
        }

        override fun newBuilder(): ImageLoader.Builder {
          return imageLoader.value.newBuilder()
        }

        override fun shutdown() {
          imageLoader.value.shutdown()
        }
      }
    }

    @Provides
    @SingleIn(AppScope::class)
    fun imageLoader(
      @ApplicationContext context: Context,
      @IsLowRamDevice isLowRamDevice: Boolean,
      okHttpClient: Lazy<OkHttpClient>,
    ): ImageLoader {
      // TODO make this like an actual builder. But for now run works...
      return ImageLoader.Builder(context).run {
        // Coil will do lazy delegation on its own under the hood, but we
        // don't need that here because we've already made it lazy. Wish this
        // wasn't the default.
        callFactory { request -> okHttpClient.value.newCall(request) }

        // Hardware bitmaps don't work with the saturation effect or palette extraction
        allowHardware(false)
        allowRgb565(isLowRamDevice)
        crossfade(AnimationConstants.DefaultDurationMillis)

        components {
          add(ImageDecoderDecoder.Factory())
          add(UnsplashSizingInterceptor)
          add(DribbbleSizingInterceptor)
        }

        build()
      }
    }

    @Provides @SingleIn(AppScope::class) fun provideClock(): Clock = Clock.System

    @Provides
    fun provideDataMode(catchUpPreferences: CatchUpPreferences): StateFlow<DataMode> {
      return catchUpPreferences.dataMode
    }

    @SingleIn(AppScope::class)
    @Provides
    fun provideLumberYard(factory: LumberYard.Factory): LumberYard {
      return factory.create(useDisk = false)
    }
  }
}

@ContributesTo(AppScope::class)
interface FakeModeModule {
  @Provides
  @FakeMode
  fun provideFakeModeStateFlow(dataMode: StateFlow<DataMode>): StateFlow<Boolean> {
    return dataMode.mapToStateFlow { it == DataMode.FAKE }
  }

  @Provides
  @FakeMode
  fun provideFakeMode(dataMode: StateFlow<DataMode>): Boolean {
    return dataMode.value == DataMode.FAKE
  }
}
