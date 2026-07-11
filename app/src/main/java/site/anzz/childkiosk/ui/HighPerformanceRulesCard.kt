package site.anzz.childkiosk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import site.anzz.childkiosk.data.WebAppEntity
import site.anzz.childkiosk.performance.HighPerformancePersistedRule

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HighPerformanceOriginRulesCard(
    rules: List<HighPerformancePersistedRule>,
    webApps: List<WebAppEntity>,
    busy: Boolean,
    onAddManual: (String, Boolean) -> Boolean,
    onAddWebApp: (WebAppEntity) -> Unit,
    onSetEnabled: (HighPerformancePersistedRule, Boolean) -> Unit,
    onSetIncludeSubdomains: (HighPerformancePersistedRule, Boolean) -> Unit,
    onRemove: (HighPerformancePersistedRule) -> Unit
) {
    var originInput by rememberSaveable { mutableStateOf("") }
    var includeSubdomains by rememberSaveable { mutableStateOf(false) }
    HighPerformanceCard {
        Text("可信网站规则", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "只按顶层网页的精确 http/https Origin 匹配；子资源、iframe、data/blob 页面不会激活。",
            fontSize = 11.sp
        )
        OutlinedTextField(
            value = originInput,
            onValueChange = { originInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("完整 Origin") },
            placeholder = { Text("https://example.com") },
            singleLine = true
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < 440.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IncludeSubdomainsControl(
                        checked = includeSubdomains,
                        enabled = !busy,
                        onCheckedChange = { includeSubdomains = it }
                    )
                    AddManualRuleButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy && originInput.isNotBlank(),
                        onClick = {
                            if (onAddManual(originInput, includeSubdomains)) {
                                originInput = ""
                                includeSubdomains = false
                            }
                        }
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IncludeSubdomainsControl(
                        modifier = Modifier.weight(1f),
                        checked = includeSubdomains,
                        enabled = !busy,
                        onCheckedChange = { includeSubdomains = it }
                    )
                    AddManualRuleButton(
                        enabled = !busy && originInput.isNotBlank(),
                        onClick = {
                            if (onAddManual(originInput, includeSubdomains)) {
                                originInput = ""
                                includeSubdomains = false
                            }
                        }
                    )
                }
            }
        }

        if (webApps.isNotEmpty()) {
            val context = androidx.compose.ui.platform.LocalContext.current
            OutlinedButton(
                onClick = {
                    WebAppSelectDialog.show(
                        context = context,
                        webApps = webApps,
                        showNewTabButton = false,
                        onWebAppSelect = { app -> onAddWebApp(app) }
                    )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("从已启用的 Web App 选择")
            }
        }

        if (rules.isEmpty()) {
            Text("尚未配置可信 Origin。", fontSize = 12.sp)
        } else {
            rules.forEach { rule ->
                HighPerformanceRuleItem(
                    rule = rule,
                    busy = busy,
                    onSetEnabled = onSetEnabled,
                    onSetIncludeSubdomains = onSetIncludeSubdomains,
                    onRemove = onRemove
                )
            }
        }
    }
}

@Composable
private fun IncludeSubdomainsControl(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text("包含子域名", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "默认关闭；公共后缀、IP 和 localhost 不允许开启",
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AddManualRuleButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        modifier = modifier.heightIn(min = 38.dp),
        enabled = enabled,
        onClick = onClick
    ) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("添加")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HighPerformanceRuleItem(
    rule: HighPerformancePersistedRule,
    busy: Boolean,
    onSetEnabled: (HighPerformancePersistedRule, Boolean) -> Unit,
    onSetIncludeSubdomains: (HighPerformancePersistedRule, Boolean) -> Unit,
    onRemove: (HighPerformancePersistedRule) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            val compact = maxWidth < 400.dp
            val title = rule.displayName?.takeIf { it.isNotBlank() } ?: rule.origin
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (compact) {
                    Column {
                        Text(
                            title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (title != rule.origin) {
                            Text(
                                rule.origin,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("启用规则", fontSize = 11.sp)
                            Spacer(Modifier.width(4.dp))
                            Switch(
                                checked = rule.enabled,
                                enabled = !busy,
                                onCheckedChange = { onSetEnabled(rule, it) }
                            )
                        }
                        OutlinedButton(
                            modifier = Modifier.heightIn(min = 38.dp),
                            enabled = !busy,
                            onClick = { onRemove(rule) }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("删除")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (title != rule.origin) {
                                Text(
                                    rule.origin,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Switch(
                            checked = rule.enabled,
                            enabled = !busy,
                            onCheckedChange = { onSetEnabled(rule, it) }
                        )
                        IconButton(enabled = !busy, onClick = { onRemove(rule) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除规则")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("匹配子域名", modifier = Modifier.weight(1f), fontSize = 11.sp)
                    Switch(
                        checked = rule.includeSubdomains,
                        enabled = !busy,
                        onCheckedChange = { onSetIncludeSubdomains(rule, it) }
                    )
                }
            }
        }
    }
}
