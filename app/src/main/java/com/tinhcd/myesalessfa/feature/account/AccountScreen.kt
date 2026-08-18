package com.tinhcd.myesalessfa.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tinhcd.myesalessfa.core.ui.PrimaryButton
import com.tinhcd.myesalessfa.core.ui.theme.MyeSalesTheme
import com.tinhcd.myesalessfa.core.ui.theme.brand
import com.tinhcd.myesalessfa.domain.model.PasswordChange
import com.tinhcd.myesalessfa.domain.model.PasswordRule
import com.tinhcd.myesalessfa.domain.model.Salesperson

/**
 * Who the rep is signed in as, and the one thing they can change about it.
 *
 * Read-only above the fold on purpose. Name, code and branch are head office's
 * record of an employee; a rep editing them in the field would be editing the key
 * that every visit, order and collection is filed under.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AccountContent(
        state = state,
        onBack = onBack,
        onCurrent = viewModel::onCurrent,
        onNew = viewModel::onNew,
        onConfirm = viewModel::onConfirm,
        onSubmit = viewModel::changePassword,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountContent(
    state: AccountUiState,
    onBack: () -> Unit,
    onCurrent: (String) -> Unit,
    onNew: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Thông tin tài khoản") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.brand.header,
                    titleContentColor = MaterialTheme.brand.onHeader,
                    navigationIconContentColor = MaterialTheme.brand.onHeader,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ProfileCard(me = state.me) }
            item { SectionTitle("Đổi mật khẩu") }
            item {
                PasswordCard(
                    change = state.change,
                    changing = state.changing,
                    changed = state.changed,
                    error = state.error,
                    onCurrent = onCurrent,
                    onNew = onNew,
                    onConfirm = onConfirm,
                    onSubmit = onSubmit,
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(me: Salesperson?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = me?.fullName ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = me?.code ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            InfoRow(Icons.Default.Badge, "Mã nhân viên", me?.code ?: "—")
            InfoRow(
                icon = Icons.Default.Business,
                label = "Chi nhánh",
                value = listOfNotNull(me?.branchCode, me?.branchName)
                    .joinToString(" · ")
                    .ifBlank { "—" },
            )
            // Not on the model yet, and shown as absent rather than hidden: a rep
            // checking why the office cannot reach them should see the field is
            // empty rather than wonder where it went.
            InfoRow(Icons.Default.Phone, "Điện thoại", "—")
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp).weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun PasswordCard(
    change: PasswordChange,
    changing: Boolean,
    changed: Boolean,
    error: String?,
    onCurrent: (String) -> Unit,
    onNew: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = change.current,
                onValueChange = onCurrent,
                label = { Text("Mật khẩu hiện tại") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = change.new,
                onValueChange = onNew,
                label = { Text("Mật khẩu mới") },
                singleLine = true,
                isError = change.sameAsCurrent,
                supportingText = {
                    if (change.sameAsCurrent) Text("Mật khẩu mới trùng mật khẩu hiện tại")
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = change.confirm,
                onValueChange = onConfirm,
                label = { Text("Nhập lại mật khẩu mới") },
                singleLine = true,
                isError = change.confirmMismatch,
                supportingText = {
                    if (change.confirmMismatch) Text("Hai mật khẩu không khớp")
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            RuleList(unmet = change.unmet, typing = change.new.isNotEmpty())

            error?.let { Notice(message = it, error = true) }
            if (changed) Notice(message = "Đã đổi mật khẩu.", error = false)

            PrimaryButton(
                text = "Đổi mật khẩu",
                onClick = onSubmit,
                enabled = change.canSubmit,
                loading = changing,
            )
        }
    }
}

/**
 * Every rule, all the time, with a tick against the ones met.
 *
 * Showing only the broken ones would make the list jump around under the rep's
 * thumb as they type, and hiding a rule the moment it passes is how someone ends
 * up unable to tell whether they satisfied it or simply stopped being told.
 */
@Composable
private fun RuleList(unmet: Set<PasswordRule>, typing: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PasswordRule.entries.forEach { rule ->
            val met = typing && rule !in unmet
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (met) Icons.Default.Check else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (met) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = rule.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (met) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Notice(message: String, error: Boolean) {
    Surface(
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (error) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

private val SampleRep = Salesperson(
    id = "s1",
    code = "nvbh01",
    fullName = "Trần Văn Nam",
    branchId = "b1",
    branchCode = "BR01",
    branchName = "NPP Miền Đông",
)

@Preview(name = "Tài khoản", showBackground = true, heightDp = 900)
@Composable
private fun AccountPreview() {
    MyeSalesTheme {
        AccountContent(
            state = AccountUiState(me = SampleRep),
            onBack = {},
            onCurrent = {},
            onNew = {},
            onConfirm = {},
            onSubmit = {},
        )
    }
}

/** Mid-typing: three rules met, two not, and the confirmation out of step. */
@Preview(name = "Tài khoản - đang nhập", showBackground = true, heightDp = 900)
@Composable
private fun AccountTypingPreview() {
    MyeSalesTheme {
        AccountContent(
            state = AccountUiState(
                me = SampleRep,
                change = PasswordChange(
                    current = "cu",
                    new = "Matkhau1",
                    confirm = "Matkhau",
                ),
            ),
            onBack = {},
            onCurrent = {},
            onNew = {},
            onConfirm = {},
            onSubmit = {},
        )
    }
}
