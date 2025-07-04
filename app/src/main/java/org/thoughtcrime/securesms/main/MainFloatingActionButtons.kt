/*
 * Copyright 2025 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.thoughtcrime.securesms.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.signal.core.ui.compose.Previews
import org.signal.core.ui.compose.SignalPreview
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.window.Navigation
import kotlin.math.roundToInt

private val ACTION_BUTTON_SIZE = 56.dp
private val ACTION_BUTTON_SPACING = 16.dp

interface MainFloatingActionButtonsCallback {
  fun onNewChatClick()
  fun onNewCallClick()
  fun onCameraClick(destination: MainNavigationListLocation)

  object Empty : MainFloatingActionButtonsCallback {
    override fun onNewChatClick() = Unit
    override fun onNewCallClick() = Unit
    override fun onCameraClick(destination: MainNavigationListLocation) = Unit
  }
}

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.thoughtcrime.securesms.conversationlist.DisplayMode
import org.thoughtcrime.securesms.database.NoteDao
import org.thoughtcrime.securesms.notes.NotesRepository
import org.thoughtcrime.securesms.profile.edit.ProfileEditActivity // Assuming this is the target activity

@Composable
fun MainFloatingActionButtons(
  destination: MainNavigationListLocation,
  mainNavViewModel: MainNavigationViewModel = viewModel(), // Get an instance of MainNavigationViewModel
  callback: MainFloatingActionButtonsCallback,
  modifier: Modifier = Modifier,
  navigation: Navigation = Navigation.rememberNavigation()
) {
  val displayMode by mainNavViewModel.currentDisplayMode.collectAsState()
  val boxHeightDp = (ACTION_BUTTON_SIZE * 2 + ACTION_BUTTON_SPACING)
  val boxHeightPx = with(LocalDensity.current) {
    boxHeightDp.toPx().roundToInt()
  }

  val primaryButtonAlignment = remember(navigation) {
    when (navigation) {
      Navigation.RAIL -> Alignment.TopCenter
      Navigation.BAR -> Alignment.BottomCenter
    }
  }

  val shadowElevation: Dp = remember(navigation) {
    when (navigation) {
      Navigation.RAIL -> 0.dp
      Navigation.BAR -> 4.dp
    }
  }

  Box(
    modifier = modifier
      .padding(ACTION_BUTTON_SPACING)
      .height(boxHeightDp)
  ) {
    SecondaryActionButton(
      destination = destination,
      boxHeightPx = boxHeightPx,
      onCameraClick = callback::onCameraClick,
      elevation = shadowElevation
    )

    Box(
      modifier = Modifier.align(primaryButtonAlignment)
    ) {
      PrimaryActionButton(
        destination = destination,
        displayMode = displayMode, // Pass displayMode
        onNewChatClick = callback::onNewChatClick,
        onCameraClick = callback::onCameraClick,
        onNewCallClick = callback::onNewCallClick,
        elevation = shadowElevation
      )
    }
  }
}

@Composable
private fun BoxScope.SecondaryActionButton(
  destination: MainNavigationListLocation,
  boxHeightPx: Int,
  elevation: Dp,
  onCameraClick: (MainNavigationListLocation) -> Unit
) {
  val navigation = Navigation.rememberNavigation()
  val secondaryButtonAlignment = remember(navigation) {
    when (navigation) {
      Navigation.RAIL -> Alignment.BottomCenter
      Navigation.BAR -> Alignment.TopCenter
    }
  }

  val offsetYProvider: (Int) -> Int = remember(navigation) {
    when (navigation) {
      Navigation.RAIL -> {
        { it - boxHeightPx }
      }
      Navigation.BAR -> {
        { boxHeightPx - it }
      }
    }
  }

  AnimatedVisibility(
    visible = destination == MainNavigationListLocation.CHATS,
    modifier = Modifier.align(secondaryButtonAlignment),
    enter = slideInVertically(initialOffsetY = offsetYProvider),
    exit = slideOutVertically(targetOffsetY = offsetYProvider)
  ) {
    val animatedElevation by transition.animateDp(targetValueByState = { if (it == EnterExitState.Visible) elevation else 0.dp })

    CameraButton(
      colors = IconButtonDefaults.filledTonalIconButtonColors().copy(
        containerColor = when (navigation) {
          Navigation.RAIL -> MaterialTheme.colorScheme.surface
          Navigation.BAR -> SignalTheme.colors.colorSurface2
        },
        contentColor = MaterialTheme.colorScheme.onSurface
      ),
      onClick = {
        onCameraClick(MainNavigationListLocation.CHATS)
      },
      shadowElevation = animatedElevation
    )
  }
}

@Composable
private fun PrimaryActionButton(
  destination: MainNavigationListLocation,
  displayMode: DisplayMode, // Add displayMode parameter
  elevation: Dp,
  onNewChatClick: () -> Unit = {},
  onCameraClick: (MainNavigationListLocation) -> Unit = {},
  onNewCallClick: () -> Unit = {}
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val onClick = remember(destination, displayMode) {
    if (destination == MainNavigationListLocation.CHATS && displayMode == DisplayMode.NOTES) {
      {
        // Create new note logic
        scope.launch {
          val notesRepository = NotesRepository(NoteDao()) // Consider how to best provide this
          val newNoteId = notesRepository.createNote("Untitled Note", "")
          // Correctly call the static Java method newNoteIntent from EditProfileActivity
          val intent = EditProfileActivity.newNoteIntent(context, org.thoughtcrime.securesms.profiles.EditMode.EDIT_NOTE, newNoteId)
          context.startActivity(intent)
        }
      }
    } else {
      when (destination) {
        MainNavigationListLocation.ARCHIVE -> error("Not supported")
        MainNavigationListLocation.CHATS -> onNewChatClick
        MainNavigationListLocation.CALLS -> onNewCallClick
        MainNavigationListLocation.STORIES -> {
          { onCameraClick(destination) }
        }
      }
    }
  }

  MainFloatingActionButton(
    onClick = onClick,
    shadowElevation = elevation,
    icon = {
      AnimatedContent(targetState = Pair(destination, displayMode)) { (targetDestination, targetDisplayMode) ->
        val (icon, contentDescriptionId) = if (targetDestination == MainNavigationListLocation.CHATS && targetDisplayMode == DisplayMode.NOTES) {
          R.drawable.ic_add_24dp to R.string.notes_list_fragment__fab_new_note // Assuming ic_add_24dp and a new string resource
        } else {
          when (targetDestination) {
            MainNavigationListLocation.ARCHIVE -> error("Not supported")
            MainNavigationListLocation.CHATS -> R.drawable.symbol_edit_24 to R.string.conversation_list_fragment__fab_content_description
            MainNavigationListLocation.CALLS -> R.drawable.symbol_phone_plus_24 to R.string.CallLogFragment__start_a_new_call
            MainNavigationListLocation.STORIES -> R.drawable.symbol_camera_24 to R.string.conversation_list_fragment__open_camera_description
          }
        }

        Icon(
          imageVector = ImageVector.vectorResource(icon),
          contentDescription = stringResource(contentDescriptionId)
        )
      }
    }
  )
}

@Composable
private fun CameraButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  shadowElevation: Dp = 4.dp,
  colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors()
) {
  MainFloatingActionButton(
    onClick = onClick,
    icon = {
      Icon(
        imageVector = ImageVector.vectorResource(R.drawable.symbol_camera_24),
        contentDescription = stringResource(R.string.conversation_list_fragment__open_camera_description)
      )
    },
    colors = colors,
    modifier = modifier,
    shadowElevation = shadowElevation
  )
}

@Composable
private fun MainFloatingActionButton(
  onClick: () -> Unit,
  icon: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  shadowElevation: Dp = 4.dp,
  colors: IconButtonColors = IconButtonDefaults.filledTonalIconButtonColors()
) {
  FilledTonalIconButton(
    onClick = onClick,
    shape = RoundedCornerShape(18.dp),
    modifier = modifier
      .size(ACTION_BUTTON_SIZE)
      .shadow(shadowElevation, RoundedCornerShape(18.dp)),
    enabled = true,
    colors = colors
  ) {
    icon()
  }
}

@SignalPreview
@Composable
private fun MainFloatingActionButtonsNavigationRailPreview() {
  var currentDestination by remember { mutableStateOf(MainNavigationListLocation.CHATS) }
  val callback = remember {
    object : MainFloatingActionButtonsCallback {
      override fun onCameraClick(destination: MainNavigationListLocation) {
        currentDestination = MainNavigationListLocation.CALLS
      }

      override fun onNewChatClick() {
        currentDestination = MainNavigationListLocation.STORIES
      }

      override fun onNewCallClick() {
        currentDestination = MainNavigationListLocation.CHATS
      }
    }
  }

  Previews.Preview {
    MainFloatingActionButtons(
      destination = currentDestination,
      callback = callback,
      navigation = Navigation.RAIL
    )
  }
}

@SignalPreview
@Composable
private fun MainFloatingActionButtonsNavigationBarPreview() {
  var currentDestination by remember { mutableStateOf(MainNavigationListLocation.CHATS) }
  val callback = remember {
    object : MainFloatingActionButtonsCallback {
      override fun onCameraClick(destination: MainNavigationListLocation) {
        currentDestination = MainNavigationListLocation.CALLS
      }

      override fun onNewChatClick() {
        currentDestination = MainNavigationListLocation.STORIES
      }

      override fun onNewCallClick() {
        currentDestination = MainNavigationListLocation.CHATS
      }
    }
  }

  Previews.Preview {
    MainFloatingActionButtons(
      destination = currentDestination,
      callback = callback,
      navigation = Navigation.BAR
    )
  }
}
