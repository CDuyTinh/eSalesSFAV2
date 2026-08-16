package com.tinhcd.myesalessfa.feature.auth

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.BuildConfig
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand

/**
 * The first thing a rep sees each morning, often standing outside in the sun with
 * one hand on the bike.
 *
 * Laid out as a branded band above a raised card rather than fields floating on a
 * flat background: the band gives the eye somewhere to land, and the card marks off
 * the part that is actually asking for something. Everything the thumb has to hit
 * sits in the lower half.
 */
@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }

    LoginContent(
        state = state,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onSubmit = viewModel::submit,
    )
}

/**
 * The screen without its ViewModel, so it can be rendered in a preview.
 *
 * That matters more than usual here: this build cannot be run on a device from the
 * machine it is written on, and a layout nobody has looked at is a layout nobody has
 * checked.
 */
@Composable
private fun LoginContent(
    state: LoginUiState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandHeader()

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // Lifted so it overlaps the header's curve rather than sitting
                // below it. The slot it leaves behind is absorbed by the smaller
                // spacer under the card.
                .offset(y = -CARD_OVERLAP),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsernameChange,
                    label = { Text("Ten dang nhap") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    // Usernames are lowercase codes; auto-capitalising them is a
                    // guaranteed failed login on the first try.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))

                var revealed by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPasswordChange,
                    label = { Text("Mat khau") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                    trailingIcon = {
                        // A real touch target. The old text toggle was a 30dp word
                        // that missed as often as it hit.
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                imageVector = if (revealed) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (revealed) {
                                    "An mat khau"
                                } else {
                                    "Hien mat khau"
                                },
                            )
                        }
                    },
                    visualTransformation = if (revealed) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onSubmit()
                        },
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Animated so the button does not jump under a thumb already on its
                // way down when an error appears.
                AnimatedVisibility(
                    visible = state.error != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    ErrorBanner(message = state.error.orEmpty())
                }

                Spacer(Modifier.height(20.dp))

                PrimaryButton(
                    text = "Dang nhap",
                    onClick = {
                        focusManager.clearFocus()
                        onSubmit()
                    },
                    enabled = state.canSubmit,
                    loading = state.loading,
                )
            }
        }

        // Not decoration: when a rep rings in about something odd, the first
        // question is always which build they are on.
        Text(
            text = "Phien ban ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        )
    }
}

/**
 * Solid brand colour rather than a gradient: it has to stay readable at midday, and
 * a flat fill survives a cheap panel that a gradient bands on.
 */
@Composable
private fun BrandHeader() {
    val brand = MaterialTheme.brand
    Surface(
        color = brand.header,
        contentColor = brand.onHeader,
        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            // Only the top inset: the brand colour is meant to run up behind the
            // status bar, but the bottom bar is nowhere near this band.
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 32.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = brand.onHeader.copy(alpha = 0.16f),
                    modifier = Modifier.fillMaxSize(),
                ) {}
                Icon(
                    imageVector = Icons.Default.Storefront,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "eSales SFA",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Dang nhap de bat dau tuyen hom nay",
                style = MaterialTheme.typography.bodyLarge,
                color = brand.onHeader.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(text = message, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private val CARD_OVERLAP = 28.dp

// -----------------------------------------------------------------------------
// Previews
//
// Three states rather than one, because the interesting failures are not in the
// empty form: the error banner pushing the button down, and the whole palette in
// dark mode where the brand band changes role.
// -----------------------------------------------------------------------------

@Preview(name = "Login", showBackground = true, device = Devices.PIXEL_7)
@Composable
private fun LoginPreview() {
    MyeSalesTheme {
        LoginContent(
            state = LoginUiState(username = "nvbh01", password = "secret"),
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
        )
    }
}

@Preview(name = "Login - error", showBackground = true, device = Devices.PIXEL_7)
@Composable
private fun LoginErrorPreview() {
    MyeSalesTheme {
        LoginContent(
            state = LoginUiState(
                username = "nvbh01",
                password = "wrong",
                error = "Sai ten dang nhap hoac mat khau",
            ),
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
        )
    }
}

@Preview(
    name = "Login - dark",
    showBackground = true,
    device = Devices.PIXEL_7,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LoginDarkPreview() {
    MyeSalesTheme(darkTheme = true) {
        LoginContent(
            state = LoginUiState(username = "nvbh01", loading = true),
            onUsernameChange = {},
            onPasswordChange = {},
            onSubmit = {},
        )
    }
}
