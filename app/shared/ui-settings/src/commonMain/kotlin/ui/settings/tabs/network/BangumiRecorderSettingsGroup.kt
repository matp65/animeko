/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.settings.tabs.network

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.PasswordVisualTransformation
import me.him188.ani.app.data.repository.br.BangumiRecorderAuth
import me.him188.ani.app.data.repository.br.BangumiRecorderSave
import me.him188.ani.app.ui.settings.framework.components.RowButtonItem
import me.him188.ani.app.ui.settings.framework.components.SettingsScope
import me.him188.ani.app.ui.settings.framework.components.TextFieldItem

@Composable
fun SettingsScope.BangumiRecorderSettingsGroup(
    save: BangumiRecorderSave,
    busy: Boolean,
    message: String?,
    onAddApiToken: (serverUrl: String, token: String) -> Unit,
    onAddJwt: (serverUrl: String, username: String, password: String) -> Unit,
    onActivate: (id: String) -> Unit,
    onRemove: (id: String) -> Unit,
    onSync: () -> Unit,
) {
    var serverUrl = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("http://127.0.0.1:8080") }
    var apiToken = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var username = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var password = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    Group(
        title = { Text("Bangumi-Recorder 配置") },
        description = { Text(message ?: "BR 是独立的第三方追番同步源，不影响 Animeko 登录状态。") },
    ) {
        TextFieldItem(
            value = serverUrl.value,
            title = { Text("服务器地址") },
            onValueChangeCompleted = { serverUrl.value = it.trimEnd('/') },
        )
        TextFieldItem(
            value = apiToken.value,
            title = { Text("API Token") },
            placeholder = { Text("使用 open API token 登录") },
            onValueChangeCompleted = { apiToken.value = it.trim() },
            visualTransformation = PasswordVisualTransformation(),
            showVisibilityToggle = true,
        )
        RowButtonItem(
            onClick = { onAddApiToken(serverUrl.value, apiToken.value) },
            enabled = !busy && serverUrl.value.isNotBlank() && apiToken.value.isNotBlank(),
            title = { Text("添加 API Token 连接并同步") },
        )
        TextFieldItem(
            value = username.value,
            title = { Text("用户名") },
            placeholder = { Text("JWT 登录用户名") },
            onValueChangeCompleted = { username.value = it.trim() },
        )
        TextFieldItem(
            value = password.value,
            title = { Text("密码") },
            placeholder = { Text("JWT 登录密码") },
            onValueChangeCompleted = { password.value = it },
            visualTransformation = PasswordVisualTransformation(),
            showVisibilityToggle = true,
        )
        RowButtonItem(
            onClick = { onAddJwt(serverUrl.value, username.value, password.value) },
            enabled = !busy && serverUrl.value.isNotBlank() && username.value.isNotBlank() && password.value.isNotBlank(),
            title = { Text("JWT 登录并同步") },
        )
        RowButtonItem(
            onClick = onSync,
            enabled = !busy && save.activeId != null,
            icon = { Icon(Icons.Outlined.Sync, null) },
            title = { Text(if (busy) "同步中..." else "手动同步当前连接") },
        )
    }

    Group(title = { Text("已保存连接") }) {
        if (save.connections.isEmpty()) {
            Item({ Text("尚未配置 BR 连接") })
        }
        save.connections.forEach { connection ->
            Item(
                headlineContent = { Text(connection.name) },
                supportingContent = {
                    Column {
                        Text(connection.serverUrl)
                        Text(if (connection.id == save.activeId) "当前连接" else "未启用")
                        Text(
                            when (connection.auth) {
                                is BangumiRecorderAuth.ApiToken -> "API Token"
                                is BangumiRecorderAuth.Jwt -> "JWT + API Token"
                            },
                        )
                    }
                },
                trailingContent = {
                    TextButton(onClick = { onRemove(connection.id) }, enabled = !busy) {
                        Icon(Icons.Outlined.Delete, null)
                        Text("删除")
                    }
                },
            )
            RowButtonItem(
                onClick = { onActivate(connection.id) },
                enabled = !busy && connection.id != save.activeId,
                title = { Text("设为当前连接") },
            )
        }
    }
}
