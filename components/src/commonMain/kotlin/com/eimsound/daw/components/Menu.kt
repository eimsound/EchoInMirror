package com.eimsound.daw.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIconDefaults
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun MenuItem(
    selected: Boolean = false,
    onClick: () -> Unit,
    onDoubleClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    minHeight: Dp = 38.dp,
    padding: PaddingValues = PaddingValues(horizontal = 12.dp),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .combinedClickable(
                enabled,
                onDoubleClick = onDoubleClick,
                onClick = onClick
            )
            .run { if (selected) background(MaterialTheme.colorScheme.secondary.copy(0.2F)) else this }
            .sizeIn(
                minWidth = 100.dp,
                maxWidth = 280.dp,
                minHeight = minHeight
            )
            .pointerHoverIcon(if (enabled) PointerIconDefaults.Hand else PointerIconDefaults.Default)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun Menu(
    menuItems: @Composable (closeDialog: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    boxModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FloatingDialog({ size, close ->
        Surface(
            Modifier.widthIn(size.width.dp).width(IntrinsicSize.Min), MaterialTheme.shapes.extraSmall,
            shadowElevation = 5.dp, tonalElevation = 5.dp
        ) {
            Box {
                val stateVertical = rememberScrollState(0)
                Column(Modifier.verticalScroll(stateVertical)) {
                    menuItems(close)
                }
            }
        }
    }, modifier = boxModifier) {
        ReadonlyTextField(content = content, modifier = modifier)
    }
}