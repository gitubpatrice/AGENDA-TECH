package com.filestech.agenda_tech.ui.screens.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import com.filestech.agenda_tech.R
import timber.log.Timber

/**
 * About screen, mirroring the Files Tech design language (cf. SMS Tech / PDF Tech): centered icon
 * header with a version pill, a privacy "badges" card, grouped feature cards, the security posture,
 * the permission list with a plain-language justification for each, then links and legal credits.
 *
 * Every claim here is verifiable from the manifest and the public source — the app declares no
 * Internet permission, so all links hand off to the browser via `ACTION_VIEW`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.editor_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            HeaderBlock(onCheckUpdate = { openUrl(context, URL_RELEASES) })

            Spacer(Modifier.size(28.dp))
            SectionTitle(stringResource(R.string.about_privacy_title))
            Spacer(Modifier.size(8.dp))
            PrivacyCard()

            Spacer(Modifier.size(24.dp))
            SectionTitle(stringResource(R.string.about_features_title))
            Spacer(Modifier.size(8.dp))
            features().forEach { FeatureRow(icon = it.icon, label = it.label, desc = it.desc) }

            Spacer(Modifier.size(24.dp))
            SectionTitle(stringResource(R.string.about_security_title))
            Spacer(Modifier.size(8.dp))
            SecurityCard()

            Spacer(Modifier.size(24.dp))
            SectionTitle(stringResource(R.string.about_permissions_title))
            Spacer(Modifier.size(8.dp))
            permissions().forEach { PermissionLine(name = it.name, why = it.why) }

            Spacer(Modifier.size(24.dp))
            SectionTitle(stringResource(R.string.about_author_title))
            Spacer(Modifier.size(8.dp))
            AuthorCard()

            Spacer(Modifier.size(24.dp))
            SectionTitle(stringResource(R.string.about_links_title))
            Spacer(Modifier.size(8.dp))
            LinkItem(
                icon = Icons.Outlined.Code,
                label = stringResource(R.string.about_source),
                trailing = Icons.Outlined.Public,
                onClick = { openUrl(context, URL_SOURCE) },
            )
            LinkItem(
                icon = Icons.Outlined.BugReport,
                label = stringResource(R.string.about_report_issue),
                onClick = { reportIssue(context) },
            )
            LinkItem(
                icon = Icons.Outlined.Language,
                label = stringResource(R.string.about_website),
                supporting = URL_WEBSITE,
                onClick = { openUrl(context, URL_WEBSITE) },
            )
            LinkItem(
                icon = Icons.Outlined.Gavel,
                label = stringResource(R.string.about_license),
                supporting = stringResource(R.string.about_license_name),
                onClick = { openUrl(context, URL_LICENSE) },
            )
            LinkItem(
                icon = Icons.Outlined.PrivacyTip,
                label = stringResource(R.string.about_privacy_policy),
                onClick = { openUrl(context, URL_PRIVACY_POLICY) },
            )

            Spacer(Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.about_credits_body, AUTHOR_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.about_legal_body, COPYRIGHT_YEAR, AUTHOR_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(24.dp))
        }
    }
}

// --- Header -----------------------------------------------------------------

@Composable
private fun HeaderBlock(onCheckUpdate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier.size(80.dp),
        )
        Spacer(Modifier.size(14.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.size(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(cs.primaryContainer)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                text = versionName(context),
                color = cs.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(14.dp))
        // Opens the releases page in the browser — the app itself never talks to the network.
        FilledTonalButton(onClick = onCheckUpdate) {
            Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.about_check_update))
        }
    }
}

// --- Privacy ----------------------------------------------------------------

@Composable
private fun PrivacyCard() {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        // Same light blue-grey as every other panel; the coloured icon and bold title carry the
        // emphasis, and the hairline outline keeps the card readable against the near-white page.
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = PrivacyGreen,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.about_privacy_card_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = cs.onSurface,
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(R.string.about_privacy_body),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.size(12.dp))
            BadgesFlow(privacyBadges())
        }
    }
}

@Composable
private fun BadgesFlow(badges: List<PrivacyBadge>) {
    // Split into two balanced rows — keeps the layout stable without pulling in FlowRow.
    val mid = (badges.size + 1) / 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(badges.take(mid), badges.drop(mid)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { Badge(icon = it.icon, label = it.label, color = it.color) }
            }
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.12f))
            .border(width = 1.dp, color = color.copy(alpha = 0.3f), shape = RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(5.dp))
        Text(text = label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

// --- Features ---------------------------------------------------------------

@Composable
private fun FeatureRow(icon: ImageVector, label: String, desc: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        // Outlined: the default card container is white in the light theme, same as the page.
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            },
            headlineContent = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                )
            },
            supportingContent = {
                Text(text = desc, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

// --- Security ---------------------------------------------------------------

@Composable
private fun SecurityCard() {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        // Same light blue-grey as every other panel; the coloured icon and bold title carry the
        // emphasis, and the hairline outline keeps the card readable against the near-white page.
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh),
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.about_security_card_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = cs.onSurface,
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.about_security_body),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

// --- Permissions ------------------------------------------------------------

@Composable
private fun PermissionLine(name: String, why: String) {
    ListItem(
        headlineContent = { Text(name, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = { Text(why, style = MaterialTheme.typography.bodySmall) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

// --- Author -----------------------------------------------------------------

@Composable
private fun AuthorCard() {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, cs.outlineVariant),
    ) {
        ListItem(
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Person, contentDescription = null, tint = cs.primary)
                }
            },
            headlineContent = { Text(AUTHOR_NAME) },
            supportingContent = { Text(stringResource(R.string.about_author_role)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

// --- Links ------------------------------------------------------------------

@Composable
private fun LinkItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    supporting: String? = null,
    trailing: ImageVector? = null,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = trailing?.let { { Icon(it, contentDescription = null) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// --- Helpers ----------------------------------------------------------------

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp),
    )
}

private fun versionName(context: Context): String {
    val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val name = info?.versionName ?: "?"
    val code = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
    return context.getString(R.string.about_version, name, code)
}

private fun reportIssue(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$CONTACT_EMAIL")).apply {
        putExtra(
            Intent.EXTRA_SUBJECT,
            "${context.getString(R.string.app_name)} ${versionName(context)}",
        )
    }
    safeStartActivity(context, intent)
}

private fun openUrl(context: Context, url: String) {
    safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

/** Never crash when no app can handle the intent (e.g. no browser / no mail client installed). */
private fun safeStartActivity(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Timber.w(it, "AboutScreen: no handler for %s", intent.action) }
}

// --- Static data ------------------------------------------------------------

private val PrivacyGreen = Color(0xFF43A047)

private const val AUTHOR_NAME = "Patrice Haltaya"
private const val COPYRIGHT_YEAR = "2026"
private const val CONTACT_EMAIL = "contact@files-tech.com"
private const val URL_WEBSITE = "https://files-tech.com"
private const val URL_SOURCE = "https://github.com/gitubpatrice/AGENDA-TECH"
private const val URL_RELEASES = "https://github.com/gitubpatrice/AGENDA-TECH/releases/latest"
private const val URL_LICENSE = "https://www.apache.org/licenses/LICENSE-2.0"
private const val URL_PRIVACY_POLICY = "https://github.com/gitubpatrice/AGENDA-TECH/blob/main/PRIVACY.md"

private data class PrivacyBadge(val icon: ImageVector, val label: String, val color: Color)

@Composable
private fun privacyBadges(): List<PrivacyBadge> = listOf(
    PrivacyBadge(Icons.Outlined.CloudOff, stringResource(R.string.about_badge_no_network), Color(0xFF1976D2)),
    PrivacyBadge(Icons.Outlined.Block, stringResource(R.string.about_badge_no_ads), Color(0xFFE53935)),
    PrivacyBadge(Icons.Outlined.VisibilityOff, stringResource(R.string.about_badge_no_tracker), Color(0xFFFF7043)),
    PrivacyBadge(Icons.Outlined.PrivacyTip, stringResource(R.string.about_badge_no_account), Color(0xFF7B1FA2)),
)

private data class Feature(val icon: ImageVector, val label: String, val desc: String)

@Composable
private fun features(): List<Feature> = listOf(
    Feature(
        Icons.Outlined.CalendarMonth,
        stringResource(R.string.about_feat_views_label),
        stringResource(R.string.about_feat_views_desc),
    ),
    Feature(
        Icons.Outlined.Repeat,
        stringResource(R.string.about_feat_recurrence_label),
        stringResource(R.string.about_feat_recurrence_desc),
    ),
    Feature(
        Icons.Outlined.Alarm,
        stringResource(R.string.about_feat_reminders_label),
        stringResource(R.string.about_feat_reminders_desc),
    ),
    Feature(
        Icons.Outlined.ImportExport,
        stringResource(R.string.about_feat_import_label),
        stringResource(R.string.about_feat_import_desc),
    ),
    Feature(
        Icons.Outlined.Widgets,
        stringResource(R.string.about_feat_widgets_label),
        stringResource(R.string.about_feat_widgets_desc),
    ),
    Feature(
        Icons.Outlined.Fingerprint,
        stringResource(R.string.about_feat_lock_label),
        stringResource(R.string.about_feat_lock_desc),
    ),
    Feature(
        Icons.Outlined.Lock,
        stringResource(R.string.about_feat_encryption_label),
        stringResource(R.string.about_feat_encryption_desc),
    ),
    Feature(
        Icons.Outlined.DarkMode,
        stringResource(R.string.about_feat_theme_label),
        stringResource(R.string.about_feat_theme_desc),
    ),
)

private data class PermissionInfo(val name: String, val why: String)

@Composable
private fun permissions(): List<PermissionInfo> = listOf(
    PermissionInfo("READ_CALENDAR", stringResource(R.string.about_perm_read_calendar)),
    PermissionInfo("POST_NOTIFICATIONS", stringResource(R.string.about_perm_notifications)),
    PermissionInfo("USE_EXACT_ALARM / SCHEDULE_EXACT_ALARM", stringResource(R.string.about_perm_exact_alarm)),
    PermissionInfo("RECEIVE_BOOT_COMPLETED", stringResource(R.string.about_perm_boot)),
    PermissionInfo("VIBRATE", stringResource(R.string.about_perm_vibrate)),
    PermissionInfo(stringResource(R.string.about_perm_no_internet_name), stringResource(R.string.about_perm_no_internet)),
)
