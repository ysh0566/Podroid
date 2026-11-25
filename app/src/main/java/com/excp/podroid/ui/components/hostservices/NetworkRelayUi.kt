package com.excp.podroid.ui.components.hostservices

import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.R
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.hostservices.*
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidSwitch
import com.excp.podroid.ui.theme.PodroidTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HostServicesViewModel @Inject constructor(
    private val settings: SettingsRepository,
    engine: VmEngine,
    network: NetworkRelay,
    microphoneSession: MicrophoneSession,
) : ViewModel() {
    val config = settings.relayConfig.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RelayConfig())
    val vmState = engine.state
    val microphone = microphoneSession.state
    val relay = network.state
    private val probeState = MutableStateFlow(ProxyProbeResult.IDLE)
    val probe = probeState.asStateFlow()
    fun resetProbe() { if (probeState.value != ProxyProbeResult.TESTING) probeState.value = ProxyProbeResult.IDLE }
    fun testProxy(value: RelayConfig) {
        if (!value.isValid() || probeState.value == ProxyProbeResult.TESTING) return
        probeState.value = ProxyProbeResult.TESTING
        viewModelScope.launch { probeState.value = probeProxy(value) }
    }
    fun save(value: RelayConfig) {
        if (vmState.value is VmState.Running || vmState.value is VmState.Starting || !value.isValid()) return
        viewModelScope.launch { settings.setRelayConfig(value) }
    }
}

@Composable
fun NetworkRelayStatus(viewModel: HostServicesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.relay.collectAsStateWithLifecycle()
    if (!config.enabled && state.phase == RelayPhase.OFF) return
    val label = when (state.phase) {
        RelayPhase.OFF -> R.string.relay_off
        RelayPhase.WAITING_NETWORK -> R.string.relay_wait_network
        RelayPhase.WAITING_PROXY -> R.string.relay_wait_proxy
        RelayPhase.READY -> R.string.relay_ready
        RelayPhase.FAILED -> R.string.relay_failed
    }
    Column {
        Text(stringResource(label))
        if (state.limit > 0) Text(
            text = stringResource(R.string.relay_stats, state.connections, state.limit, state.bytes / 1048576.0, state.rejected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun NetworkRelaySettings(viewModel: HostServicesViewModel = hiltViewModel()) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    PodroidListRow(
        label = stringResource(R.string.relay_title),
        value = stringResource(if (config.enabled) R.string.on else R.string.off),
        trailing = "›",
        onClick = { showDialog = true },
    )
    if (showDialog) {
        NetworkRelayDialog(config, viewModel, onDismiss = { showDialog = false })
    }
}

@Composable
private fun NetworkRelayDialog(
    config: RelayConfig,
    viewModel: HostServicesViewModel,
    onDismiss: () -> Unit,
) {
    val vm by viewModel.vmState.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    val vmStopped = vm !is VmState.Running && vm !is VmState.Starting
    val editable = vmStopped && probe != ProxyProbeResult.TESTING
    // Keep edits across rotation, but discard unsaved fields when the dialog closes.
    var host by rememberSaveable(config.host) { mutableStateOf(config.host) }
    var port by rememberSaveable(config.port) { mutableStateOf(config.port.toString()) }
    var noProxy by rememberSaveable(config.noProxy) { mutableStateOf(config.noProxy) }
    var connectionLimit by rememberSaveable(config.connectionLimit) { mutableStateOf(config.connectionLimit) }
    var showLimits by remember { mutableStateOf(false) }
    val candidate = RelayConfig(config.enabled, host.trim(), port.toIntOrNull() ?: 0, noProxy, connectionLimit)
    LaunchedEffect(candidate) { viewModel.resetProbe() }
    LaunchedEffect(editable) { if (!editable) showLimits = false }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relay_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
            ) {
                PodroidListRow(
                    label = stringResource(R.string.proxy_use),
                    onClick = { if (editable) viewModel.save(config.copy(enabled = !config.enabled)) },
                    rightSlot = {
                        PodroidSwitch(
                            checked = config.enabled,
                            enabled = editable,
                            onCheckedChange = { viewModel.save(config.copy(enabled = it)) },
                        )
                    },
                )
                ProxyHint(stringResource(R.string.relay_description))
                if (!vmStopped) {
                    ProxyHint(stringResource(R.string.stop_vm_to_change))
                }
                if (config.enabled) {
                    PodroidListRow(
                        label = stringResource(R.string.proxy_type),
                        value = stringResource(R.string.proxy_http),
                    )
                    Box {
                        PodroidListRow(
                            label = stringResource(R.string.proxy_connection_limit),
                            value = if (connectionLimit == AUTO_RELAY_CONNECTIONS) {
                                stringResource(R.string.auto)
                            } else connectionLimit.toString(),
                            trailing = "›",
                            onClick = if (editable) ({ showLimits = true }) else null,
                        )
                        DropdownMenu(expanded = showLimits, onDismissRequest = { showLimits = false }) {
                            RELAY_CONNECTION_OPTIONS.forEach { limit ->
                                DropdownMenuItem(
                                    text = {
                                        Text(if (limit == AUTO_RELAY_CONNECTIONS) stringResource(R.string.auto) else limit.toString())
                                    },
                                    enabled = editable,
                                    onClick = { connectionLimit = limit; showLimits = false },
                                )
                            }
                        }
                    }
                    ProxyHint(stringResource(R.string.proxy_connection_limit_hint))
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        enabled = editable,
                        label = { Text(stringResource(R.string.relay_proxy_host)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        enabled = editable,
                        label = { Text(stringResource(R.string.relay_proxy_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = noProxy,
                        onValueChange = { noProxy = it },
                        enabled = editable,
                        label = { Text(stringResource(R.string.proxy_bypass)) },
                        supportingText = { Text(stringResource(R.string.proxy_bypass_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM)) {
                        TextButton(
                            enabled = editable && candidate.isValid() && candidate != config,
                            onClick = { viewModel.save(candidate) },
                        ) { Text(stringResource(R.string.relay_save)) }
                        TextButton(
                            enabled = candidate.isValid() && probe != ProxyProbeResult.TESTING,
                            onClick = { viewModel.testProxy(candidate) },
                        ) { Text(stringResource(R.string.proxy_test)) }
                    }
                    ProxyHint(stringResource(R.string.proxy_test_hint))
                    val probeLabel = when (probe) {
                        ProxyProbeResult.IDLE -> null
                        ProxyProbeResult.TESTING -> R.string.proxy_testing
                        ProxyProbeResult.UNREACHABLE -> R.string.proxy_unreachable
                        ProxyProbeResult.HTTPS_FAILED -> R.string.proxy_https_failed
                        ProxyProbeResult.SUCCESS -> R.string.proxy_success
                    }
                    probeLabel?.let { Text(stringResource(it)) }
                    NetworkRelayStatus(viewModel)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun ProxyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun MicrophoneButton(viewModel: HostServicesViewModel = hiltViewModel()) {
    val phase by viewModel.microphone.collectAsStateWithLifecycle()
    val vm by viewModel.vmState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val active = phase == MicrophonePhase.WAITING || phase == MicrophonePhase.RECORDING
    val start = {
        try { MicrophoneService.start(context); Unit }
        catch (_: RuntimeException) {
            android.widget.Toast.makeText(context, R.string.mic_failed, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) start()
        else android.widget.Toast.makeText(context, R.string.mic_permission, android.widget.Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(phase) {
        if (phase == MicrophonePhase.FAILED)
            android.widget.Toast.makeText(context, R.string.mic_failed, android.widget.Toast.LENGTH_LONG).show()
    }
    androidx.compose.material3.IconButton(enabled = vm is VmState.Running || active, onClick = {
        if (active) MicrophoneService.stop(context)
        else if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(android.Manifest.permission.RECORD_AUDIO)
    }) {
        androidx.compose.material3.Icon(
            imageVector = if (active) androidx.compose.material.icons.Icons.Default.Mic else androidx.compose.material.icons.Icons.Default.MicOff,
            contentDescription = stringResource(if (active) R.string.mic_stop else R.string.mic_start),
            tint = if (active) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.material3.LocalContentColor.current,
        )
    }
}
