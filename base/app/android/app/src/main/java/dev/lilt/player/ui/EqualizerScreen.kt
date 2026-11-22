package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.library.EQ_PRESETS
import dev.lilt.player.library.PreampMode
import kotlinx.coroutines.delay

private val bandLabels = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

@Composable
fun EqualizerScreen(vm: LiltViewModel, onBack: () -> Unit) {
    val eq by vm.eq.collectAsStateWithLifecycle()
    val profile = eq.profile
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshEq()
            delay(3_000)
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("Equalizer", "均衡器"), onBack = onBack) },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("Ten-band equalizer", "十段均衡器"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        t(
                            "Changes apply to the current output immediately.",
                            "修改会立即应用到当前输出。",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = profile.enabled, onCheckedChange = vm::setEqEnabled)
            }

            EqSection(t("Output", "输出")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(eq.deviceName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            t(
                                "The profile follows the active output device.",
                                "配置会跟随当前输出设备。",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Switch(
                            checked = eq.perDevice,
                            onCheckedChange = vm::setEqPerDevice,
                        )
                        Text(
                            t("Per device", "按设备"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            EqSection(t("Preamp", "前级")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreampMode.entries.forEach { mode ->
                        ChoicePill(
                            label = when (mode) {
                                PreampMode.Off -> t("Off", "关闭")
                                PreampMode.Headroom -> t("Headroom", "余量")
                                PreampMode.Boost -> t("Boost", "提升")
                            },
                            selected = profile.preampMode == mode,
                        ) { vm.setEqPreampMode(mode) }
                    }
                }
                when (profile.preampMode) {
                    PreampMode.Headroom -> LabeledSlider(
                        label = t("Headroom", "余量"),
                        valueText = "-%.0f dB".format(profile.headroomDb),
                        value = profile.headroomDb,
                        range = 1f..30f,
                        onChange = vm::setEqHeadroom,
                    )
                    PreampMode.Boost -> LabeledSlider(
                        label = t("Boost", "提升"),
                        valueText = "+%.0f dB".format(profile.boostDb),
                        value = profile.boostDb,
                        range = 1f..9f,
                        onChange = vm::setEqBoost,
                    )
                    PreampMode.Off -> Text(
                        t(
                            "No preamp gain is applied.",
                            "当前不应用前级增益。",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                profile.suggestedHeadroomDb()?.let { suggestion ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t(
                                "Suggested headroom: -%.0f dB".format(suggestion),
                                "建议余量：-%.0f dB".format(suggestion),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            vm.setEqHeadroom(suggestion)
                            vm.setEqPreampMode(PreampMode.Headroom)
                        }) {
                            Text(t("Use", "采用"))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            EqSection(t("Presets", "预设")) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(EQ_PRESETS.keys.toList()) { name ->
                        ChoicePill(
                            label = name,
                            selected = profile.preset == name,
                        ) { vm.setEqPreset(name) }
                    }
                }
                Text(
                    t(
                        "22 curves from the desktop build. Moving a band marks the profile as Custom.",
                        "与桌面版一致的 22 条曲线。手动调整频段后会标记为自定义。",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))

            EqSection(t("Bands", "频段")) {
                profile.gains.forEachIndexed { index, gain ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${bandLabels[index]} Hz",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(74.dp),
                        )
                        Slider(
                            value = gain,
                            onValueChange = { vm.setEqGain(index, it) },
                            valueRange = -15f..15f,
                            modifier = Modifier.weight(1f),
                            enabled = profile.enabled,
                        )
                        Text(
                            "%+.1f dB".format(gain),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(68.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            EqSection(t("Tone", "音色")) {
                LabeledSlider(
                    label = t("Bass boost", "低音增强"),
                    valueText = "${(profile.bass * 100).toInt()}%",
                    value = profile.bass,
                    range = 0f..1f,
                    onChange = vm::setEqBass,
                )
                LabeledSlider(
                    label = t("Virtual surround", "虚拟环绕"),
                    valueText = if (!eq.virtualizerAvailable) {
                        t("Not supported", "此设备不支持")
                    } else {
                        "${(profile.virtualizer * 100).toInt()}%"
                    },
                    value = profile.virtualizer,
                    range = 0f..1f,
                    enabled = eq.virtualizerAvailable && profile.enabled,
                    onChange = vm::setEqVirtualizer,
                )
                val balanceText = when {
                    profile.balance < 99.5f -> "L ${(100f - profile.balance).toInt()}"
                    profile.balance > 100.5f -> "R ${(profile.balance - 100f).toInt()}"
                    else -> t("Center", "居中")
                }
                LabeledSlider(
                    label = t("Balance", "平衡"),
                    valueText = balanceText,
                    value = profile.balance,
                    range = 0f..200f,
                    onChange = vm::setEqBalance,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("L", style = MaterialTheme.typography.labelSmall)
                    Text("C", style = MaterialTheme.typography.labelSmall)
                    Text("R", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(10.dp))

            Text(
                t(
                    "Ten bands from 31.5 Hz to 16 kHz, ±15 dB. Preamp, bass and balance are applied per channel; the surround strength uses the device effect when available. Profiles and the current device selection are stored locally and restored when the service restarts.",
                    "十段 31.5 Hz 至 16 kHz，±15 dB。前级、低音和平衡会按声道应用；环绕强度在设备支持时使用系统音效。配置与当前设备选择保存在本机，服务重启后恢复。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun EqSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        )
    }
}
