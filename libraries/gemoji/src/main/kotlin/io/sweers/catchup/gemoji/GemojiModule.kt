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
package io.sweers.catchup.gemoji

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.squareup.anvil.annotations.ContributesTo
import dagger.Module
import dagger.Provides
import dev.zacsweers.catchup.di.AppScope
import dev.zacsweers.catchup.di.SingleIn
import dev.zacsweers.catchup.gemoji.db.GemojiDatabase
import io.sweers.catchup.util.injection.qualifiers.ApplicationContext

@ContributesTo(AppScope::class)
@Module
object GemojiModule {

  @Provides
  @SingleIn(AppScope::class)
  internal fun provideGemojiDatabase(@ApplicationContext context: Context): GemojiDatabase {
    val delegate =
      FrameworkSQLiteOpenHelperFactory()
        .create(
          SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("gemoji.db")
            .callback(
              object : SupportSQLiteOpenHelper.Callback(GemojiDatabase.Schema.version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                  // Do nothing
                }

                override fun onUpgrade(
                  db: SupportSQLiteDatabase,
                  oldVersion: Int,
                  newVersion: Int
                ) {
                  // Do nothing
                }
              }
            )
            .build()
        )
    val assetAssistedOpenHelper =
      SQLiteCopyOpenHelper(
        context = context,
        copyFromAssetPath = "databases/gemoji.db",
        databaseVersion = GemojiDatabase.Schema.version,
        delegate = delegate
      )

    return GemojiDatabase(AndroidSqliteDriver(assetAssistedOpenHelper))
  }
}
