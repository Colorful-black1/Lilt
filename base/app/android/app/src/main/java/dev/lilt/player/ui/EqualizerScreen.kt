package dev.lilt.player.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel

private val bandLabels = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

@Composable
fun EqualizerScreen(vm: LiltViewModel, onBack: () -> Unit) {
    val eq by vm.eq.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("Equalizer", "均衡器"), onBack = onBack) },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("Ten-band equalizer", "十段均衡器"), style = MaterialTheme.typography.titleLarge)
                    Text(
                        t("Changes apply to the current output immediately.", "修改会立即应用到当前输出。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = eq.enabled, onCheckedChange = vm::setEqEnabled)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LiltViewModel.EQ_PRESETS.keys.take(4).forEach { name ->
                    ChoicePill(name, selected = false) { vm.setEqPreset(name) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LiltViewModel.EQ_PRESETS.keys.drop(4).forEach { name ->
                    ChoicePill(name, selected = false) { vm.setEqPreset(name) }
                }
            }
            Spacer(Modifier.height(12.dp))
            eq.gains.forEachIndexed { index, gain ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                            enabled = eq.enabled,
                        )
                        Text(
                            "%+.1f dB".format(gain),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(66.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                t(
                    "The curve is kept for this phone and restored when the service restarts. Ten bands: 31.5 Hz to 16 kHz, ±15 dB.",
                    "曲线保存在这台手机上，服务重启后自动恢复。共十段：31.5 Hz 至 16 kHz，±15 dB。",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}
