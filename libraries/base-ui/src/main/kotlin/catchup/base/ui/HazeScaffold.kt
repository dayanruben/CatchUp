// Copyright 2023, Christopher Banes and the Tivi project contributors
// SPDX-License-Identifier: Apache-2.0
package catchup.base.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

@Composable
fun HazeScaffold(
  modifier: Modifier = Modifier,
  topBar: @Composable () -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  snackbarHost: @Composable () -> Unit = {},
  floatingActionButton: @Composable () -> Unit = {},
  floatingActionButtonPosition: FabPosition = FabPosition.End,
  containerColor: Color = MaterialTheme.colorScheme.background,
  contentColor: Color = contentColorFor(containerColor),
  contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
  blurTopBar: Boolean = false,
  blurBottomBar: Boolean = false,
  content: @Composable (PaddingValues) -> Unit,
) {
  val hazeState = remember { HazeState() }
  val bgColor = MaterialTheme.colorScheme.surface
  val style =
    remember(bgColor, blurTopBar) {
      HazeStyle(
        backgroundColor = bgColor,
        tint = HazeDefaults.tint(containerColor),
        blurRadius = HazeDefaults.blurRadius,
        noiseFactor = HazeDefaults.noiseFactor,
      )
    }

  NestedScaffold(
    modifier = modifier,
    topBar = {
      Box(
        modifier = Modifier.thenIf(blurTopBar) { hazeEffect(hazeState, style = style) },
        content = { topBar() },
      )
    },
    bottomBar = {
      Box(
        modifier = Modifier.thenIf(blurBottomBar) { hazeEffect(hazeState, style) },
        content = { bottomBar() },
      )
    },
    snackbarHost = snackbarHost,
    floatingActionButton = floatingActionButton,
    floatingActionButtonPosition = floatingActionButtonPosition,
    containerColor = containerColor,
    contentColor = contentColor,
    contentWindowInsets = contentWindowInsets,
  ) { contentPadding ->
    Box(modifier = Modifier.hazeSource(state = hazeState), content = { content(contentPadding) })
  }
}
