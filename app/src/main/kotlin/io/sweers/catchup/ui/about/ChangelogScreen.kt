package io.sweers.catchup.ui.about

import android.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.apollographql.apollo3.ApolloClient
import com.apollographql.apollo3.exception.ApolloException
import com.slack.circuit.CircuitUiEvent
import com.slack.circuit.CircuitUiState
import com.slack.circuit.Presenter
import com.slack.circuit.Screen
import com.slack.circuit.codegen.annotations.CircuitInject
import com.squareup.anvil.annotations.ContributesBinding
import dev.zacsweers.catchup.di.AppScope
import dev.zacsweers.catchup.service.ClickableItem
import dev.zacsweers.catchup.service.ErrorItem
import dev.zacsweers.catchup.service.TextItem
import io.sweers.catchup.R
import io.sweers.catchup.data.LinkManager
import io.sweers.catchup.data.github.RepoReleasesQuery
import io.sweers.catchup.gemoji.EmojiMarkdownConverter
import io.sweers.catchup.gemoji.replaceMarkdownEmojisIn
import io.sweers.catchup.service.api.CatchUpItem
import io.sweers.catchup.service.api.UrlMeta
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import timber.log.Timber

@Parcelize
object ChangelogScreen : Screen {
  data class State(val items: ImmutableList<CatchUpItem>?, val eventSink: (Event) -> Unit) :
    CircuitUiState
  sealed interface Event : CircuitUiEvent {
    data class Click(val url: String) : Event
  }
}

@CircuitInject(ChangelogScreen::class, AppScope::class)
class ChangelogPresenter
@Inject
constructor(
  private val linkManager: LinkManager,
  private val changelogRepository: ChangelogRepository,
) : Presenter<ChangelogScreen.State> {
  @Composable
  override fun present(): ChangelogScreen.State {
    // TODO use paging?
    val items by
      produceState<ImmutableList<CatchUpItem>?>(null) { value = changelogRepository.requestItems() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return ChangelogScreen.State(items) { event ->
      when (event) {
        is ChangelogScreen.Event.Click -> {
          scope.launch { linkManager.openUrl(UrlMeta(event.url, Color.BLACK, context)) }
        }
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@CircuitInject(ChangelogScreen::class, AppScope::class)
@Composable
fun Changelog(state: ChangelogScreen.State, modifier: Modifier = Modifier) {
  val sink = state.eventSink
  LazyColumn(modifier = modifier) {
    val items = state.items
    if (items == null) {
      item {
        Box(Modifier.fillParentMaxSize()) {
          CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
      }
    } else if (items.isEmpty()) {
      item {
        Box(Modifier.fillParentMaxSize()) {
          ErrorItem(
            text = "Could not load changelog.",
            modifier = Modifier.align(Alignment.Center),
            onRetryClick = null,
          )
        }
      }
    } else {
      items(
        count = items.size,
        key = { items[it].id },
      ) { index ->
        val item = items[index]
        ClickableItem(
          modifier = Modifier.animateItemPlacement(),
          onClick = { sink(ChangelogScreen.Event.Click(item.clickUrl!!)) },
        ) {
          TextItem(item, colorResource(R.color.colorAccent))
        }
      }
    }
  }
}

interface ChangelogRepository {
  suspend fun requestItems(): ImmutableList<CatchUpItem>
}

@ContributesBinding(AppScope::class)
class ChangelogRepositoryImpl
@Inject
constructor(
  private val apolloClient: ApolloClient,
  private val markdownConverter: EmojiMarkdownConverter,
) : ChangelogRepository {
  override suspend fun requestItems(): ImmutableList<CatchUpItem> {
    return try {
      requestItemsInner()
    } catch (e: ApolloException) {
      Timber.tag("ChangelogRepository").e(e, "Error fetching changelog")
      persistentListOf()
    }
  }

  private suspend fun requestItemsInner(): ImmutableList<CatchUpItem> {
    return apolloClient
      .query(RepoReleasesQuery())
      .execute()
      .let {
        @Suppress("UNCHECKED_CAST")
        it.data!!.repository!!.onRepository.releases.nodes as List<RepoReleasesQuery.Node>
      }
      .mapIndexed { index, node ->
        with(node.onRelease) {
          CatchUpItem(
            id = tag!!.target.abbreviatedOid.hashCode().toLong(),
            title = markdownConverter.replaceMarkdownEmojisIn(name!!),
            timestamp = publishedAt!!,
            tag = tag.name,
            source = tag.target.abbreviatedOid, // sha
            itemClickUrl = url.toString(),
            // TODO revisit when we have expandable items and markdown support
            //  description = markdownConverter.replaceMarkdownEmojisIn(description!!),
            serviceId = "changelog",
            indexInResponse = index,
          )
        }
      }
      .toImmutableList()
  }
}