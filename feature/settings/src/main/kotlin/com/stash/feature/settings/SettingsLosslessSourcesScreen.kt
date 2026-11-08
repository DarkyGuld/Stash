package com.stash.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.addons.EclipseAddon
import com.stash.core.data.addons.EclipseAddonSetting
import com.stash.core.data.prefs.StreamSourceToggle
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.StashSwitch
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.components.LosslessRoutingStatus
import com.stash.feature.settings.components.SettingsScaffold

/**
 * Settings > Audio & Quality > Lossless sources: where FLAC comes from, kept
 * off the main quality screen so it stays short. The routing rows, today's
 * shared allowance, the supporter unlock, the listener's own Qobuz account,
 * optional Eclipse addons, and (collapsed) Advanced: a custom endpoint, a
 * BitChord server, the captcha cookie, and resetting lossless attempts.
 */
@Composable
fun SettingsLosslessSourcesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val qbdlxExpired by viewModel.qbdlxExpired.collectAsStateWithLifecycle()
    val qobuzConnectedEmail by viewModel.qobuzConnectedEmail.collectAsStateWithLifecycle()
    val qobuzHasLogin by viewModel.qobuzHasLogin.collectAsStateWithLifecycle()
    val losslessRouting by viewModel.losslessRouting.collectAsStateWithLifecycle()
    val qobuzConnecting by viewModel.qobuzConnecting.collectAsStateWithLifecycle()
    val qobuzConnectError by viewModel.qobuzConnectError.collectAsStateWithLifecycle()
    val customEndpoint by viewModel.customEndpoint.collectAsStateWithLifecycle()
    val customEndpointError by viewModel.customEndpointError.collectAsStateWithLifecycle()
    val customEndpointTest by viewModel.customEndpointTest.collectAsStateWithLifecycle()
    val bitchordEndpoint by viewModel.bitchordEndpoint.collectAsStateWithLifecycle()
    val bitchordEndpointError by viewModel.bitchordEndpointError.collectAsStateWithLifecycle()
    val bitchordHasToken by viewModel.bitchordHasToken.collectAsStateWithLifecycle()
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val addonBusy by viewModel.addonBusy.collectAsStateWithLifecycle()
    val addonError by viewModel.addonError.collectAsStateWithLifecycle()
    val addonsFirst by viewModel.addonsFirst.collectAsStateWithLifecycle()
    val cloudFastestSource by viewModel.cloudFastestSource.collectAsStateWithLifecycle()
    val qobuzEnabled by viewModel.qobuzEnabled.collectAsStateWithLifecycle()
    val tidalEnabled by viewModel.tidalEnabled.collectAsStateWithLifecycle()
    val eclipseAddonsEnabled by viewModel.eclipseAddonsEnabled.collectAsStateWithLifecycle()
    val jiosaavnEnabled by viewModel.jiosaavnEnabled.collectAsStateWithLifecycle()
    val youtubeEnabled by viewModel.youtubeEnabled.collectAsStateWithLifecycle()
    val eclipseTokenPresent by viewModel.eclipseTokenPresent.collectAsStateWithLifecycle()
    val eclipseTokenError by viewModel.eclipseTokenError.collectAsStateWithLifecycle()
    val eclipseUserEmail by viewModel.eclipseUserEmail.collectAsStateWithLifecycle()
    val eclipseLoginBusy by viewModel.eclipseLoginBusy.collectAsStateWithLifecycle()
    val eclipseLoginError by viewModel.eclipseLoginError.collectAsStateWithLifecycle()
    val quotaViewModel: LosslessQuotaViewModel = hiltViewModel()
    val losslessToday by quotaViewModel.line.collectAsStateWithLifecycle()

    // The sign-in WebView replaces the screen while it runs. Its URL is
    // saveable so a rotation mid-flow doesn't drop the user back into Settings
    // (the WebView reloads the entry point; the provider session survives in
    // the cookie jar).
    var eclipseLoginUrl by rememberSaveable { mutableStateOf<String?>(null) }
    if (eclipseLoginUrl != null) {
        EclipseLoginWebView(
            url = eclipseLoginUrl!!,
            onToken = viewModel::onEclipseTokenCommitted,
            onDismiss = { eclipseLoginUrl = null },
        )
        return
    }

    SettingsScaffold(title = stringResource(R.string.settings_lossless_sources_title), onBack = onBack, modifier = modifier) {
        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ROUTING block — one row per configured lossless path,
                // built in LosslessAvailability so this list and the
                // resolver read the same predicates.
                LosslessRoutingStatus(rows = losslessRouting)

                // The daily lossless allowance (500 free a day, shared by the relays).
                // Hidden when no relay is configured or nothing is known yet.
                losslessToday?.let { line ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Unlimited for Ko-fi supporters: the claim, or the relays' "supporter" status.
                SupporterSection()

                // Direct Qobuz — direct www.qobuz.com Hi-Res FLAC, the
                // primary lossless source. The badge shows when no
                // lossless path is configured (`LosslessAvailability
                // .qbdlxEnabled`). No per-source toggle: a stale saved `false` with
                // no UI to flip it back would kill lossless silently.
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Same gate as the card subtitle.
                    if (qbdlxExpired) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_qobuz_no_source_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // -- Bring your own Qobuz account -------------
                    // The user's own paid subscription: the one
                    // credential guaranteed to serve FLAC, since we
                    // mint + sign its token under a matching app_id.
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_qobuz_account_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // Keyed on hasLogin, NOT on the email: a token migrated
                    // from the old paste field has no email, and keying on
                    // that showed its owner a sign-in form with no way to
                    // remove the token when it went dead.
                    if (qobuzHasLogin) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = qobuzConnectedEmail?.let { stringResource(R.string.settings_connected_as, it) }
                                ?: stringResource(R.string.settings_qobuz_connected_token),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        TextButton(onClick = viewModel::onDisconnectQobuz) {
                            Text(stringResource(R.string.settings_disconnect))
                        }
                    } else {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_qobuz_signin_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        var qobuzEmail by remember { mutableStateOf("") }
                        var qobuzPassword by remember { mutableStateOf("") }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = qobuzEmail,
                            onValueChange = { qobuzEmail = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_qobuz_email_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = qobuzPassword,
                            onValueChange = { qobuzPassword = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_password_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        qobuzConnectError?.let { err ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = { viewModel.onConnectQobuz(qobuzEmail, qobuzPassword) },
                            enabled = !qobuzConnecting,
                        ) {
                            if (qobuzConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.settings_connecting))
                            } else {
                                Text(stringResource(R.string.settings_connect))
                            }
                        }
                    }
                }

            }
        }

        // -- Sources ---------------------------------------------------
        // Inclusion, not order: each switch removes its rung from the
        // fallback chain (the addons card below owns the addon ordering).
        GlassCard {
            SourcesSection(
                qobuzEnabled = qobuzEnabled,
                tidalEnabled = tidalEnabled,
                eclipseAddonsEnabled = eclipseAddonsEnabled,
                jiosaavnEnabled = jiosaavnEnabled,
                youtubeEnabled = youtubeEnabled,
                onChanged = viewModel::onSourceEnabledChanged,
            )
        }

        // -- Eclipse addons ---------------------------------------------
        // Optional extra music sources (Nebula/TorBox, SpotiFLAC, …). They
        // sit between Qobuz/Tidal and the lossy fallbacks, so the section
        // lives here, right after the Qobuz card and before Advanced.
        GlassCard {
            EclipseAddonsSection(
                addons = addons,
                busy = addonBusy,
                error = addonError,
                addonsFirst = addonsFirst,
                cloudFastestSource = cloudFastestSource,
                tokenPresent = eclipseTokenPresent,
                tokenError = eclipseTokenError,
                userEmail = eclipseUserEmail,
                loginBusy = eclipseLoginBusy,
                loginError = eclipseLoginError,
                onAdd = viewModel::onAddAddon,
                onRemove = viewModel::onRemoveAddon,
                onToggle = viewModel::onToggleAddon,
                onMove = viewModel::onMoveAddon,
                onErrorShown = viewModel::onAddonErrorShown,
                onAddonsFirstChanged = viewModel::onAddonsFirstChanged,
                onCloudFastestSourceChanged = viewModel::onCloudFastestSourceChanged,
                onTokenCommitted = viewModel::onEclipseTokenCommitted,
                onPasswordLogin = viewModel::onEclipsePasswordLogin,
                onSignOut = viewModel::onEclipseSignOut,
                onSignIn = { eclipseLoginUrl = it },
                onSettingChanged = viewModel::onAddonSettingChanged,
            )
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                // -- Advanced expander row (chevron + label) -----------
                var advancedExpanded by remember(uiState.losslessEnabled) { mutableStateOf(false) }
                val chevronRotation by animateFloatAsState(
                    targetValue = if (advancedExpanded) 90f else 0f,
                    label = "advancedChevron",
                )
                val advancedExpandedDesc = stringResource(if (advancedExpanded) R.string.settings_state_expanded else R.string.settings_state_collapsed)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded }
                        .semantics {
                            role = Role.Button
                            // Spec §Accessibility: announce collapsed/expanded
                            // state to screen readers.
                            stateDescription = advancedExpandedDesc
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null, // parent Row carries role + stateDescription + label
                        modifier = Modifier.graphicsLayer(rotationZ = chevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.settings_advanced),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(
                    visible = advancedExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // -- Custom lossless endpoint -----------------
                        // Outranks every relay from runtime config in
                        // QbdlxFileUrlRouter, so a typo here silently
                        // costs the user their first lossless attempt —
                        // hence Test, and hence commit-on-Done.
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_custom_relay_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        // The VM's committed value is the seed AND the
                        // comparison for commit(): re-keying on it means the
                        // field snaps to what was actually stored WHEN THE
                        // STORED VALUE CHANGES — a trailing slash normalises
                        // to the same string, so that edit is swallowed by
                        // distinctUntilChanged and the slash stays on screen.
                        // Saveable, not remember: MainActivity declares no
                        // configChanges, so a rotation mid-URL threw the
                        // half-typed draft away. A relay base is not a secret.
                        var endpointDraft by rememberSaveable(customEndpoint) {
                            mutableStateOf(customEndpoint.orEmpty())
                        }
                        // The error lives in the VM and outlives this subtree:
                        // collapsing Advanced and reopening re-seeds the draft
                        // from storage but left the rejection on screen — an
                        // empty field painted red. Clear it on (re)entry.
                        LaunchedEffect(Unit) { viewModel.onCustomEndpointEdited() }
                        var endpointWasFocused by remember { mutableStateOf(false) }
                        val focusManager = LocalFocusManager.current
                        val commitEndpoint = {
                            val draft = endpointDraft.trim()
                            // Per keystroke this would persist `https://re` as a
                            // base and route every resolve at it. The guard skips
                            // the common no-op; it does NOT make this idempotent,
                            // since the Done path commits and then clears focus
                            // before `customEndpoint` has re-emitted. The repeat
                            // is an identical write, collapsed downstream.
                            if (draft != customEndpoint.orEmpty()) {
                                viewModel.onCustomEndpointCommitted(draft)
                            }
                        }
                        OutlinedTextField(
                            value = endpointDraft,
                            onValueChange = {
                                endpointDraft = it
                                // Otherwise the red "Must be an https:// URL"
                                // outlives the text it judged — including onto
                                // an empty field after a collapse/reopen.
                                viewModel.onCustomEndpointEdited()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                // `endpointWasFocused` gates the initial unfocused
                                // callback, which would otherwise commit "".
                                .onFocusChanged { state ->
                                     if (endpointWasFocused && !state.isFocused) commitEndpoint()
                                    endpointWasFocused = state.isFocused
                                },
                            label = { Text(stringResource(R.string.settings_custom_endpoint_label)) },
                            singleLine = true,
                            isError = customEndpointError != null,
                            placeholder = { Text(stringResource(R.string.settings_url_placeholder)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    commitEndpoint()
                                    focusManager.clearFocus()
                                },
                            ),
                        )
                        customEndpointError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val testing =
                                customEndpointTest == SettingsViewModel.EndpointTestState.TESTING
                            TextButton(
                                onClick = viewModel::onTestCustomEndpoint,
                                enabled = customEndpoint != null && !testing,
                            ) {
                                Text(stringResource(R.string.settings_test))
                            }
                            // Reachability, not health: any HTTP reply counts.
                            when (customEndpointTest) {
                                SettingsViewModel.EndpointTestState.TESTING -> Text(
                                    text = stringResource(R.string.settings_testing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SettingsViewModel.EndpointTestState.REACHABLE -> Text(
                                    text = stringResource(R.string.settings_reachable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StashTheme.extendedColors.success,
                                )
                                SettingsViewModel.EndpointTestState.UNREACHABLE -> Text(
                                    text = stringResource(R.string.settings_not_reachable),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                SettingsViewModel.EndpointTestState.IDLE -> Unit
                            }
                        }


                        // -- Tidal via the user's own BitChord server -----
                        // The second lossless rung (after Qobuz). Same
                        // commit-on-Done / focus-loss rule as the endpoint
                        // above: a half-typed base must never be stored.
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.settings_bitchord_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        var bitchordDraft by rememberSaveable(bitchordEndpoint) {
                            mutableStateOf(bitchordEndpoint.orEmpty())
                        }
                        LaunchedEffect(Unit) { viewModel.onBitchordEndpointEdited() }
                        var bitchordWasFocused by remember { mutableStateOf(false) }
                        val commitBitchord = {
                            val draft = bitchordDraft.trim()
                            if (draft != bitchordEndpoint.orEmpty()) {
                                viewModel.onBitchordEndpointCommitted(draft)
                            }
                        }
                        OutlinedTextField(
                            value = bitchordDraft,
                            onValueChange = {
                                bitchordDraft = it
                                viewModel.onBitchordEndpointEdited()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    if (bitchordWasFocused && !state.isFocused) commitBitchord()
                                    bitchordWasFocused = state.isFocused
                                },
                            label = { Text(stringResource(R.string.settings_bitchord_server_label)) },
                            singleLine = true,
                            isError = bitchordEndpointError != null,
                            placeholder = { Text(stringResource(R.string.settings_url_placeholder)) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    commitBitchord()
                                    focusManager.clearFocus()
                                },
                            ),
                        )
                        bitchordEndpointError?.let { err ->
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // Write-only: a stored token is never shown back,
                        // only that one is set. Blank + Done clears it.
                        var tokenDraft by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = tokenDraft,
                            onValueChange = { tokenDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_access_token_label)) },
                            supportingText = {
                                Text(stringResource(if (bitchordHasToken) R.string.settings_bitchord_token_saved else R.string.settings_bitchord_token_hint))
                            },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    viewModel.onBitchordTokenCommitted(tokenDraft)
                                    tokenDraft = ""
                                    focusManager.clearFocus()
                                },
                            ),
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_squid_captcha_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = uiState.squidWtfCaptchaCookie,
                            onValueChange = viewModel::onSquidWtfCaptchaCookieChanged,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_squid_captcha_label)) },
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.settings_squid_captcha_placeholder)) },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = viewModel::onResetLosslessRateLimiter,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_reset_lossless_attempts),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The streaming chain's rungs, each with an on/off switch. Inclusion only:
 * the resolver's order is untouched, and the Eclipse card's "Play addons
 * first" switch still owns the addon rung's position.
 */
@Composable
private fun SourcesSection(
    qobuzEnabled: Boolean,
    tidalEnabled: Boolean,
    eclipseAddonsEnabled: Boolean,
    jiosaavnEnabled: Boolean,
    youtubeEnabled: Boolean,
    onChanged: (StreamSourceToggle, Boolean) -> Unit,
) {
    val dividerColor = StashTheme.extendedColors.glassBorder
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_sources_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.settings_sources_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SourceToggleRow(
            label = stringResource(R.string.settings_source_qobuz),
            checked = qobuzEnabled,
            onCheckedChange = { onChanged(StreamSourceToggle.QOBUZ, it) },
        )
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
        SourceToggleRow(
            label = stringResource(R.string.settings_source_tidal),
            checked = tidalEnabled,
            onCheckedChange = { onChanged(StreamSourceToggle.TIDAL, it) },
        )
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
        SourceToggleRow(
            label = stringResource(R.string.settings_source_eclipse_addons),
            checked = eclipseAddonsEnabled,
            onCheckedChange = { onChanged(StreamSourceToggle.ECLIPSE_ADDONS, it) },
        )
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
        SourceToggleRow(
            label = stringResource(R.string.settings_source_jiosaavn),
            checked = jiosaavnEnabled,
            onCheckedChange = { onChanged(StreamSourceToggle.JIOSAAVN, it) },
        )
        HorizontalDivider(color = dividerColor, thickness = 1.dp)
        SourceToggleRow(
            label = stringResource(R.string.settings_source_youtube),
            checked = youtubeEnabled,
            onCheckedChange = { onChanged(StreamSourceToggle.YOUTUBE, it) },
        )
    }
}

@Composable
private fun SourceToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Whole row toggles, like SettingsToggleRow; the switch's own tap
            // is consumed by the switch, so one tap never toggles twice.
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        StashSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * Eclipse Music addons: paste an addon URL, then toggle, reorder or remove
 * what's installed. The list order is the fallback order (top first), which
 * the description says out loud so the arrows make sense without a legend.
 */
@Composable
private fun EclipseAddonsSection(
    addons: List<EclipseAddon>,
    busy: Boolean,
    error: String?,
    addonsFirst: Boolean,
    cloudFastestSource: Boolean,
    tokenPresent: Boolean,
    tokenError: String?,
    userEmail: String?,
    loginBusy: Boolean,
    loginError: String?,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onErrorShown: () -> Unit,
    onAddonsFirstChanged: (Boolean) -> Unit,
    onCloudFastestSourceChanged: (Boolean) -> Unit,
    onTokenCommitted: (String) -> Unit,
    onPasswordLogin: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onSignIn: (String) -> Unit,
    onSettingChanged: (String, String, String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var draft by rememberSaveable { mutableStateOf("") }
    // The addon whose settings dialog is open (id, so a rename/reorder can't
    // point the dialog at the wrong row).
    var settingsAddonId by rememberSaveable { mutableStateOf<String?>(null) }
    // The URL of the last Add attempt: the field is emptied for the request so
    // the spinner has somewhere to land, and a rejection puts the text back so
    // a typo can be fixed without pasting again.
    var lastSubmitted by rememberSaveable { mutableStateOf<String?>(null) }
    var wasBusy by remember { mutableStateOf(false) }

    LaunchedEffect(busy, error) {
        if (busy) {
            wasBusy = true
            return@LaunchedEffect
        }
        // Only a real request completion matters; entering the screen with
        // busy = false says nothing.
        if (!wasBusy) return@LaunchedEffect
        wasBusy = false
        val rejected = lastSubmitted
        lastSubmitted = null
        if (error == null) {
            // Installed: get the keyboard out of the way so the new row is
            // visible without a manual scroll.
            focusManager.clearFocus()
        } else if (rejected != null && draft.isBlank()) {
            // Rejected: hand the URL back for editing, unless the user has
            // already started typing something else.
            draft = rejected
        }
    }

    val submit = {
        val raw = draft.trim()
        lastSubmitted = raw.takeIf { it.isNotBlank() }
        draft = ""
        onErrorShown()
        onAdd(raw)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_addons_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.settings_addons_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Priority switch: the one line that changes where addons sit in the
        // resolver order. Above the account/Add controls because it frames
        // everything the list below does.
        Spacer(modifier = Modifier.height(10.dp))
        EclipseAddonsFirstRow(
            checked = addonsFirst,
            onCheckedChange = onAddonsFirstChanged,
        )

        // Cloud's own accelerated path: race the Apple stream against Tidal.
        Spacer(modifier = Modifier.height(10.dp))
        EclipseCloudFastestRow(
            checked = cloudFastestSource,
            onCheckedChange = onCloudFastestSourceChanged,
        )

        // Account-bound addons (Eclipse Cloud) need a token. The WebView
        // sign-in and the paste fallback both land in the same store; the
        // token itself is never shown back.
        Spacer(modifier = Modifier.height(12.dp))
        EclipseAccountBlock(
            tokenPresent = tokenPresent,
            tokenError = tokenError,
            userEmail = userEmail,
            loginBusy = loginBusy,
            loginError = loginError,
            onTokenCommitted = onTokenCommitted,
            onPasswordLogin = onPasswordLogin,
            onSignOut = onSignOut,
            onSignIn = onSignIn,
        )

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(
            color = StashTheme.extendedColors.glassBorder,
            thickness = 1.dp,
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // A rejection describes the old text; drop it as soon as
                    // the user starts editing.
                    onErrorShown()
                },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.settings_addon_url_label)) },
                placeholder = { Text(stringResource(R.string.settings_addon_url_placeholder)) },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Tall enough to line up with the field. Enabled on a blank field
            // so tapping gives the "enter the URL" hint instead of nothing.
            Button(
                onClick = submit,
                enabled = !busy,
                modifier = Modifier.height(56.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_addon_adding))
                } else {
                    Text(stringResource(R.string.settings_addon_add))
                }
            }
        }
        error?.let { message ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        if (addons.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_addons_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val dividerColor = StashTheme.extendedColors.glassBorder
            addons.forEachIndexed { index, addon ->
                if (index > 0) {
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                }
                EclipseAddonRow(
                    addon = addon,
                    canMoveUp = index > 0,
                    canMoveDown = index < addons.lastIndex,
                    onToggle = { onToggle(addon.id, it) },
                    onMoveUp = { onMove(addon.id, -1) },
                    onMoveDown = { onMove(addon.id, 1) },
                    onRemove = { onRemove(addon.id) },
                    onOpenSettings = { settingsAddonId = addon.id },
                )
            }
        }
    }

    // Re-read from the list by id: a setting change lands on the addon in the
    // repository, and the dialog has to show the value it actually stored.
    val settingsAddon = settingsAddonId?.let { id -> addons.firstOrNull { it.id == id } }
    if (settingsAddon != null) {
        EclipseAddonSettingsDialog(
            addon = settingsAddon,
            onSettingChanged = { key, value ->
                onSettingChanged(settingsAddon.id, key, value)
            },
            onDismiss = { settingsAddonId = null },
        )
    }
}

@Composable
private fun EclipseAddonsFirstRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_addons_first_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_addons_first_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        StashSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun EclipseCloudFastestRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_cloud_fastest_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_cloud_fastest_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        StashSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun EclipseAccountBlock(
    tokenPresent: Boolean,
    tokenError: String?,
    userEmail: String?,
    loginBusy: Boolean,
    loginError: String?,
    onTokenCommitted: (String) -> Unit,
    onPasswordLogin: (String, String) -> Unit,
    onSignOut: () -> Unit,
    onSignIn: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val passwordFocus = remember { FocusRequester() }
    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    var tokenDraft by rememberSaveable { mutableStateOf("") }
    var emailOpen by rememberSaveable { mutableStateOf(false) }
    var emailDraft by rememberSaveable { mutableStateOf("") }
    // A password is a secret: kept in composition only, never saved.
    var passwordDraft by remember { mutableStateOf("") }

    val save = {
        val raw = tokenDraft.trim()
        if (raw.isNotEmpty()) {
            onTokenCommitted(raw)
            tokenDraft = ""
            pasteOpen = false
            focusManager.clearFocus()
        }
    }
    val submitLogin = {
        if (emailDraft.isNotBlank() && passwordDraft.isNotBlank() && !loginBusy) {
            onPasswordLogin(emailDraft.trim(), passwordDraft)
            // Don't leave the password sitting in a field after submitting.
            passwordDraft = ""
            focusManager.clearFocus()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_eclipse_account_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = when {
                        !tokenPresent -> stringResource(R.string.settings_eclipse_sign_in_desc)
                        !userEmail.isNullOrBlank() -> stringResource(
                            R.string.settings_eclipse_signed_in_as,
                            userEmail,
                        )
                        else -> stringResource(R.string.settings_eclipse_signed_in)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    // Signed in is stated in words; the colour is only a nudge.
                    color = if (tokenPresent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tokenPresent) {
                TextButton(onClick = onSignOut) {
                    Text(stringResource(R.string.settings_eclipse_sign_out))
                }
            }
        }

        if (!tokenPresent) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onSignIn(ECLIPSE_LOGIN_GOOGLE) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_eclipse_sign_in_google))
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { onSignIn(ECLIPSE_LOGIN_DISCORD) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.settings_eclipse_sign_in_discord))
                }
            }
            // Email/password is the path when the providers' consent can't run
            // in this WebView (or the user never linked a provider at all).
            TextButton(onClick = { emailOpen = !emailOpen }) {
                Text(
                    text = stringResource(R.string.settings_eclipse_sign_in_email),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (emailOpen) {
                OutlinedTextField(
                    value = emailDraft,
                    onValueChange = { emailDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_eclipse_email_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = passwordDraft,
                    onValueChange = { passwordDraft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocus),
                    label = { Text(stringResource(R.string.settings_eclipse_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = submitLogin,
                        enabled = !loginBusy &&
                            emailDraft.isNotBlank() &&
                            passwordDraft.isNotBlank(),
                    ) {
                        if (loginBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.settings_eclipse_signing_in))
                        } else {
                            Text(stringResource(R.string.settings_eclipse_sign_in))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            // No in-app recovery page; the site owns it.
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(ECLIPSE_WEB_LOGIN)),
                                )
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.settings_eclipse_forgot_password),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            // Escape hatch when the provider blocks the WebView (same reason
            // the other accounts keep a paste path visible).
            TextButton(onClick = { pasteOpen = !pasteOpen }) {
                Text(
                    text = stringResource(R.string.settings_eclipse_paste_token),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (pasteOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.settings_eclipse_token_label)) },
                        singleLine = true,
                        // A token is a password: mask it, never echo it.
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = save,
                        enabled = tokenDraft.isNotBlank(),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text(stringResource(R.string.settings_eclipse_save))
                    }
                }
            }
        }

        (loginError ?: tokenError)?.let { message ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EclipseAddonRow(
    addon: EclipseAddon,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val enabledLabel = stringResource(R.string.settings_addon_enabled)
    // Two lines: the name + host own the width, and the actions sit on their
    // own row so a fourth icon (settings) can't squeeze the name on a narrow
    // phone. The switch stays on the first line, where the eye looks.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = addon.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The switch position already says it, but a word makes the
                    // disabled state readable without relying on colour.
                    if (!addon.enabled) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_addon_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Host only, never the manifest URL: the path or query can hold
                // a private token (Nebula does). displayHost strips everything else.
                Text(
                    text = addon.displayHost,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StashSwitch(
                checked = addon.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { contentDescription = "${addon.name}, $enabledLabel" },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (addon.settingDefs.isNotEmpty()) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.settings_addon_settings),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(
                onClick = onMoveUp,
                enabled = canMoveUp,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.settings_addon_move_up),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = canMoveDown,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.settings_addon_move_down),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = stringResource(R.string.settings_addon_remove),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The manifest settings one installed addon declares. Values apply on change —
 * there is no Save — so the dialog always reflects what the repository holds
 * (the caller re-reads the addon from the list on every emission).
 */
@Composable
private fun EclipseAddonSettingsDialog(
    addon: EclipseAddon,
    onSettingChanged: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                text = stringResource(R.string.settings_addon_settings_title, addon.name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                addon.settingDefs.forEach { setting ->
                    key(setting.key) {
                        EclipseAddonSettingField(
                            setting = setting,
                            current = addon.settings[setting.key] ?: setting.default.orEmpty(),
                            onChanged = { value -> onSettingChanged(setting.key, value) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        },
    )
}

@Composable
private fun EclipseAddonSettingField(
    setting: EclipseAddonSetting,
    current: String,
    onChanged: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val label = setting.label.ifBlank { setting.key }

    Column(modifier = Modifier.fillMaxWidth()) {
        when (setting.type) {
            "toggle" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StashSwitch(
                    checked = current == "true",
                    onCheckedChange = { onChanged(it.toString()) },
                )
            }

            "select" -> {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                SelectSettingField(
                    setting = setting,
                    current = current,
                    onChanged = onChanged,
                )
            }

            // "text", "number", and any type this build doesn't know yet.
            else -> {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                var draft by rememberSaveable(setting.key) { mutableStateOf(current) }
                var wasFocused by remember { mutableStateOf(false) }
                val commit = {
                    val value = draft.trim()
                    if (value != current) onChanged(value)
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { new ->
                        // The manifest's cap is a hard stop, not a post-hoc error.
                        draft = setting.maxLength?.let { new.take(it) } ?: new
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state ->
                            if (wasFocused && !state.isFocused) commit()
                            wasFocused = state.isFocused
                        },
                    placeholder = {
                        Text(stringResource(R.string.settings_addon_setting_text_hint))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (setting.type == "number") {
                            KeyboardType.Number
                        } else {
                            KeyboardType.Text
                        },
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            commit()
                            focusManager.clearFocus()
                        },
                    ),
                )
            }
        }

        setting.help?.takeIf { it.isNotBlank() }?.let { help ->
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Stash keeps one value per setting; say so instead of pretending the
        // per-network split exists.
        if (setting.perNetwork) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.settings_addon_setting_per_network),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectSettingField(
    setting: EclipseAddonSetting,
    current: String,
    onChanged: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = setting.options.firstOrNull { it.value == current }?.label
        ?: current.ifBlank { null }

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selectedLabel
                    ?: stringResource(R.string.settings_addon_setting_text_hint),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            setting.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onChanged(option.value)
                        expanded = false
                    },
                    trailingIcon = if (option.value == current) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
