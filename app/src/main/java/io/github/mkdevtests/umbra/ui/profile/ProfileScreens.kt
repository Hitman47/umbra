package io.github.mkdevtests.umbra.ui.profile

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mkdevtests.umbra.MainActivity
import io.github.mkdevtests.umbra.profile.PROFILE_COLORS
import io.github.mkdevtests.umbra.profile.Profile
import io.github.mkdevtests.umbra.ui.perso.MAX_PIN
import io.github.mkdevtests.umbra.ui.perso.MIN_PIN
import io.github.mkdevtests.umbra.ui.perso.PinDots
import io.github.mkdevtests.umbra.ui.perso.PinPad
import io.github.mkdevtests.umbra.ui.perso.askFingerprint
import io.github.mkdevtests.umbra.ui.perso.fingerprintAvailable
import io.github.mkdevtests.umbra.ui.theme.focusRing
import kotlinx.coroutines.delay

/** A profile's circle: its initial on its colour. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp = 88.dp) {
    Box(
        modifier = Modifier.size(size).background(Color(PROFILE_COLORS[profile.color % PROFILE_COLORS.size]), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(profile.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.42f).sp)
    }
}

/**
 * "Qui regarde ?": the profiles in circles; a locked one asks its PIN (or the
 * fingerprint) before [onChosen].
 */
@Composable
fun ProfilePicker(profiles: List<Profile>, active: Profile, onChosen: (Profile) -> Unit, onCancel: (() -> Unit)? = null) {
    var unlocking by remember { mutableStateOf<Profile?>(null) }
    val first = remember { FocusRequester() }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Qui regarde ?", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            profiles.forEach { profile ->
                Column(
                    modifier = Modifier
                        .width(128.dp)
                        .then(if (profile.id == active.id) Modifier.focusRequester(first) else Modifier)
                        .focusRing(RoundedCornerShape(20.dp))
                        .clickable { if (profile.locked) unlocking = profile else onChosen(profile) }
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProfileAvatar(profile)
                    Text(
                        profile.name + if (profile.locked) " 🔒" else "",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.child) Text("Enfant · ${profile.maxAge} ans", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        onCancel?.let { TextButton(onClick = it, modifier = Modifier.focusRing(RoundedCornerShape(50))) { Text("Annuler") } }
    }
    LaunchedEffect(Unit) {
        delay(200)
        runCatching { first.requestFocus() }
    }
    unlocking?.let { profile ->
        PinDialog(profile, onDismiss = { unlocking = null }) {
            unlocking = null
            onChosen(profile)
        }
    }
}

/** The PIN of [profile] (or its fingerprint), before it opens. */
@Composable
fun PinDialog(profile: Profile, title: String = profile.name, onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    LaunchedEffect(profile.id) {
        if (profile.fingerprint && fingerprintAvailable(context)) askFingerprint(context, "Ouvrir $title", onUnlocked)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PinDots(typed.length, wrong)
                PinPad(
                    onDigit = { if (typed.length < MAX_PIN) { typed += it; wrong = false } },
                    onErase = { typed = typed.dropLast(1) },
                    onValidate = {
                        if (typed.length >= MIN_PIN && profile.checks(typed)) onUnlocked() else { wrong = true; typed = "" }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(RoundedCornerShape(50))) { Text("Annuler") } },
    )
}

/** Changing profile: Nyxara starts again on it (each profile's data is read at start). */
object ProfileSwitch {
    const val EXTRA_CHOSEN = "profile_chosen"

    /** [chosen]: the profile is the one to open; false shows "Qui regarde ?" again. */
    fun restart(activity: Activity, chosen: Boolean = true) {
        val intent = Intent(activity, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(EXTRA_CHOSEN, chosen)
        activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}
