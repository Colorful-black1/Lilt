package dev.lilt.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lilt.player.LiltViewModel
import dev.lilt.player.library.FolderNode
import dev.lilt.player.library.FolderTree

private data class FlatFolder(val node: FolderNode, val depth: Int)

@Composable
fun RulesScreen(vm: LiltViewModel, onBack: () -> Unit) {
    val songs by vm.allSongs.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val includeOnly by vm.includeOnly.collectAsStateWithLifecycle()
    val tree = remember(songs) { FolderTree.build(songs) }
    val folders = remember(tree) {
        val out = mutableListOf<FlatFolder>()
        fun walk(nodes: List<FolderNode>, depth: Int) {
            nodes.forEach {
                out += FlatFolder(it, depth)
                walk(it.children, depth + 1)
            }
        }
        walk(tree, 0)
        out
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars),
        topBar = { AppBar(title = t("Folder rules", "文件夹规则"), onBack = onBack) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t("Only included folders", "仅显示包含的文件夹"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        t("Changes apply to the library immediately.", "修改会立即应用到曲库。"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = includeOnly, onCheckedChange = vm::setIncludeOnly)
            }
            if (folders.isEmpty()) {
                EmptyState(
                    title = t("No folders to rule", "没有可设置的文件夹"),
                    body = t("Scan your device first.", "请先扫描设备。"),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(folders, key = { it.node.path }) { folder ->
                        val excluded = rules.any { it.path == folder.node.path && it.kind == "exclude" }
                        val included = rules.any { it.path == folder.node.path && it.kind == "include" }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = (12 + folder.depth * 18).dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                folder.node.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            RuleChip(
                                label = t("Exclude", "排除"),
                                active = excluded,
                                activeColor = MaterialTheme.colorScheme.error,
                            ) {
                                vm.setFolderRule(folder.node.path, "exclude", !excluded)
                            }
                            Spacer(Modifier.width(6.dp))
                            RuleChip(
                                label = t("Include", "包含"),
                                active = included,
                                activeColor = MaterialTheme.colorScheme.primary,
                            ) {
                                vm.setFolderRule(folder.node.path, "include", !included)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RuleChip(label: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) activeColor else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
