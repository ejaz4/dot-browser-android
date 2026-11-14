package co.dothq.browser.gecko

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import co.dothq.browser.BrowserActivity
import co.dothq.browser.R
import co.dothq.browser.core.Urls
import co.dothq.browser.ui.Fonts
import co.dothq.browser.ui.Haptics
import co.dothq.browser.ui.Radius
import co.dothq.browser.ui.choiceList
import co.dothq.browser.ui.dp
import co.dothq.browser.ui.flp
import co.dothq.browser.ui.icon
import co.dothq.browser.ui.label
import co.dothq.browser.ui.lp
import co.dothq.browser.ui.margins
import co.dothq.browser.ui.oval
import co.dothq.browser.ui.ripple
import co.dothq.browser.ui.rounded
import co.dothq.browser.ui.sinkOnPress
import co.dothq.browser.ui.withAlpha
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.util.Calendar
import java.util.Locale

/** Completes a GeckoResult at most once (dialogs report both a button and a dismiss). */
private class Once<T>(val result: GeckoResult<T> = GeckoResult()) {
    private var done = false
    fun complete(value: T) {
        if (done) return
        done = true
        result.complete(value)
    }
}

/** Prompts and permissions for web pages, drawn in Dot's style. */
class WindowDelegates(private val a: BrowserActivity) : PromptDelegate, PermissionDelegate {

    private fun dialog() = MaterialAlertDialogBuilder(a)
    private fun site(session: GeckoSession): String =
        a.tabForSession(session)?.host?.ifEmpty { null } ?: a.getString(R.string.app_name)

    /* ---- Prompts ---------------------------------------------------------------------------- */

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        dialog().setTitle(prompt.title?.ifBlank { null } ?: site(session))
            .setMessage(prompt.message)
            .setPositiveButton(R.string.ok) { _, _ -> once.complete(prompt.dismiss()) }
            .setOnDismissListener { once.complete(prompt.dismiss()) }
            .show()
        return once.result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        dialog().setTitle(prompt.title?.ifBlank { null } ?: site(session))
            .setMessage(prompt.message)
            .setPositiveButton(R.string.ok) { _, _ -> once.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
            .setNegativeButton(R.string.cancel) { _, _ -> once.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
            .setOnDismissListener { once.complete(prompt.dismiss()) }
            .show()
        return once.result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        val input = field(prompt.defaultValue ?: "", null)
        dialog().setTitle(prompt.title?.ifBlank { null } ?: site(session))
            .setMessage(prompt.message)
            .setView(padded(input))
            .setPositiveButton(R.string.ok) { _, _ -> once.complete(prompt.confirm(input.text.toString())) }
            .setNegativeButton(R.string.cancel) { _, _ -> once.complete(prompt.dismiss()) }
            .setOnDismissListener { once.complete(prompt.dismiss()) }
            .show()
        return once.result
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: PromptDelegate.AuthPrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        val onlyPassword = prompt.authOptions.flags and PromptDelegate.AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        val user = field(prompt.authOptions.username ?: "", a.getString(R.string.username))
        val pass = field(prompt.authOptions.password ?: "", a.getString(R.string.password)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            if (!onlyPassword) addView(user)
            addView(pass)
        }
        dialog().setTitle(R.string.sign_in)
            .setMessage(prompt.message)
            .setView(padded(box))
            .setPositiveButton(R.string.sign_in) { _, _ ->
                once.complete(
                    if (onlyPassword) prompt.confirm(pass.text.toString())
                    else prompt.confirm(user.text.toString(), pass.text.toString())
                )
            }
            .setNegativeButton(R.string.cancel) { _, _ -> once.complete(prompt.dismiss()) }
            .setOnDismissListener { once.complete(prompt.dismiss()) }
            .show()
        return once.result
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        val flat = mutableListOf<PromptDelegate.ChoicePrompt.Choice>()
        fun walk(cs: Array<PromptDelegate.ChoicePrompt.Choice>?) {
            cs?.forEach { c ->
                when {
                    c.items != null -> walk(c.items)
                    !c.separator && !c.disabled -> flat.add(c)
                }
            }
        }
        walk(prompt.choices)
        if (flat.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())

        if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            val checked = BooleanArray(flat.size) { flat[it].selected }
            dialog().setTitle(prompt.title?.ifBlank { null } ?: site(session))
                .setMultiChoiceItems(flat.map { it.label }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
                .setPositiveButton(R.string.ok) { _, _ ->
                    once.complete(prompt.confirm(flat.filterIndexed { i, _ -> checked[i] }.map { it.id }.toTypedArray()))
                }
                .setNegativeButton(R.string.cancel) { _, _ -> once.complete(prompt.dismiss()) }
                .setOnDismissListener { once.complete(prompt.dismiss()) }
                .show()
            return once.result
        }

        // Single choice (<select>) as a Dot sheet.
        val p = a.palette()
        val sheet = a.sheet
        val title = prompt.title?.ifBlank { null } ?: prompt.message?.ifBlank { null } ?: site(session)
        val view = choiceList(a, p, title, flat.map { it.label to null }, flat.indexOfFirst { it.selected }) { i ->
            once.complete(prompt.confirm(flat[i]))
            sheet.dismiss()
        }
        sheet.onDismiss = { once.complete(prompt.dismiss()); sheet.onDismiss = null }
        sheet.show(scrollable(view))
        return once.result
    }

    override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        val cal = Calendar.getInstance()
        val def = prompt.defaultValue ?: ""
        runCatching {
            Regex("""(\d{4})-(\d{2})(?:-(\d{2}))?""").find(def)?.let { m ->
                cal.set(Calendar.YEAR, m.groupValues[1].toInt())
                cal.set(Calendar.MONTH, m.groupValues[2].toInt() - 1)
                m.groupValues[3].takeIf { it.isNotEmpty() }?.let { cal.set(Calendar.DAY_OF_MONTH, it.toInt()) }
            }
            Regex("""(\d{2}):(\d{2})""").find(def)?.let { m ->
                cal.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
                cal.set(Calendar.MINUTE, m.groupValues[2].toInt())
            }
        }
        val type = prompt.type
        fun pickTime(prefix: String) {
            TimePickerDialog(a, { _, h, m ->
                once.complete(prompt.confirm(prefix + String.format(Locale.US, "%02d:%02d", h, m)))
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(a)).apply {
                setOnDismissListener { once.complete(prompt.dismiss()) }
            }.show()
        }
        if (type == PromptDelegate.DateTimePrompt.Type.TIME) {
            pickTime("")
            return once.result
        }
        var picked = false
        DatePickerDialog(a, { _, y, mo, d ->
            picked = true
            val c = Calendar.getInstance().apply { set(y, mo, d) }
            when (type) {
                PromptDelegate.DateTimePrompt.Type.MONTH -> once.complete(prompt.confirm(String.format(Locale.US, "%04d-%02d", y, mo + 1)))
                PromptDelegate.DateTimePrompt.Type.WEEK -> {
                    c.minimalDaysInFirstWeek = 4
                    c.firstDayOfWeek = Calendar.MONDAY
                    once.complete(prompt.confirm(String.format(Locale.US, "%04d-W%02d", c.getWeekYear(), c.get(Calendar.WEEK_OF_YEAR))))
                }
                PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> pickTime(String.format(Locale.US, "%04d-%02d-%02dT", y, mo + 1, d))
                else -> once.complete(prompt.confirm(String.format(Locale.US, "%04d-%02d-%02d", y, mo + 1, d)))
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            setOnDismissListener { if (!picked || type != PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL) once.complete(prompt.dismiss()) }
        }.show()
        return once.result
    }

    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        val mimes = prompt.mimeTypes?.filter { it.contains('/') }?.ifEmpty { null } ?: listOf("*/*")
        a.pickFiles(mimes.toTypedArray(), multiple) { uris: List<Uri> ->
            when {
                uris.isEmpty() -> once.complete(prompt.dismiss())
                multiple -> once.complete(prompt.confirm(a, uris.toTypedArray()))
                else -> once.complete(prompt.confirm(a, uris.first()))
            }
        }
        return once.result
    }

    override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse> {
        a.toast("Dot blocked a pop-up")
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
    }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        dialog().setTitle(R.string.leave_page_title)
            .setMessage(R.string.leave_page_body)
            .setPositiveButton(R.string.leave) { _, _ -> once.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .setNegativeButton(R.string.stay) { _, _ -> once.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .setOnDismissListener { once.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .show()
        return once.result
    }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: PromptDelegate.RepostConfirmPrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        dialog().setTitle(R.string.resend_title)
            .setMessage(R.string.resend_body)
            .setPositiveButton(R.string.resend) { _, _ -> once.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .setNegativeButton(R.string.cancel) { _, _ -> once.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .setOnDismissListener { once.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .show()
        return once.result
    }

    override fun onSharePrompt(session: GeckoSession, prompt: PromptDelegate.SharePrompt): GeckoResult<PromptResponse> {
        val text = listOfNotNull(prompt.text, prompt.uri).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            prompt.title?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        return try {
            a.startActivity(Intent.createChooser(send, prompt.title))
            GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.SUCCESS))
        } catch (_: Exception) {
            GeckoResult.fromValue(prompt.confirm(PromptDelegate.SharePrompt.Result.FAILURE))
        }
    }

    override fun onColorPrompt(session: GeckoSession, prompt: PromptDelegate.ColorPrompt): GeckoResult<PromptResponse> {
        val once = Once<PromptResponse>()
        val swatches = listOf(
            "#000000", "#5F6368", "#FFFFFF", "#E5484D", "#F2893A", "#E9C230",
            "#1FB364", "#16B3A3", "#2D8FE0", "#256EF5", "#6A5CE0", "#E0526E",
        )
        val p = a.palette()
        val grid = android.widget.GridLayout(a).apply {
            columnCount = 6
            setPadding(20.dp, 4.dp, 20.dp, 12.dp)
        }
        swatches.forEach { hex ->
            grid.addView(FrameLayout(a).apply {
                background = oval(android.graphics.Color.parseColor(hex)).apply { setStroke(1, p.border) }
                setOnClickListener { Haptics.tick(it); once.complete(prompt.confirm(hex.lowercase())); a.sheet.dismiss() }
            }, android.widget.GridLayout.LayoutParams().apply {
                width = 40.dp; height = 40.dp; setMargins(6.dp, 6.dp, 6.dp, 6.dp)
            })
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(a.label(prompt.title?.ifBlank { null } ?: "Choose a colour", 18f, p.text, bold = true).apply {
                setPadding(24.dp, 10.dp, 24.dp, 8.dp)
            })
            addView(grid)
        }
        a.sheet.onDismiss = { once.complete(prompt.dismiss()); a.sheet.onDismiss = null }
        a.sheet.show(box)
        return once.result
    }

    /* ---- Permissions ------------------------------------------------------------------------ */

    override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: PermissionDelegate.Callback) {
        if (permissions.isNullOrEmpty()) { callback.grant(); return }
        a.requestAndroidPermissions(permissions.toList().toTypedArray()) { granted ->
            if (granted) callback.grant() else callback.reject()
        }
    }

    override fun onContentPermissionRequest(session: GeckoSession, perm: PermissionDelegate.ContentPermission): GeckoResult<Int>? {
        val host = Urls.host(perm.uri).ifEmpty { site(session) }
        val (icon, text) = when (perm.permission) {
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> return GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_ALLOW)
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> return GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
            PermissionDelegate.PERMISSION_TRACKING -> return null
            PermissionDelegate.PERMISSION_GEOLOCATION -> R.drawable.ic_location to a.getString(R.string.permission_location, host)
            PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> R.drawable.ic_info to a.getString(R.string.permission_notifications, host)
            PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> R.drawable.ic_key to a.getString(R.string.permission_drm, host)
            PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> R.drawable.ic_folder to a.getString(R.string.permission_storage, host)
            else -> R.drawable.ic_shield to a.getString(R.string.permission_generic, host)
        }
        val once = Once<Int>()
        ask(icon, text) { allow ->
            once.complete(
                when (allow) {
                    true -> PermissionDelegate.ContentPermission.VALUE_ALLOW
                    false -> PermissionDelegate.ContentPermission.VALUE_DENY
                    null -> PermissionDelegate.ContentPermission.VALUE_PROMPT
                }
            )
        }
        return once.result
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession, uri: String,
        video: Array<out PermissionDelegate.MediaSource>?, audio: Array<out PermissionDelegate.MediaSource>?,
        callback: PermissionDelegate.MediaCallback,
    ) {
        val host = Urls.host(uri).ifEmpty { site(session) }
        val text = when {
            video != null && audio != null -> a.getString(R.string.permission_camera_microphone, host)
            video != null -> a.getString(R.string.permission_camera, host)
            else -> a.getString(R.string.permission_microphone, host)
        }
        ask(if (video != null) R.drawable.ic_camera else R.drawable.ic_info, text) { allow ->
            if (allow == true) callback.grant(video?.firstOrNull(), audio?.firstOrNull()) else callback.reject()
        }
    }

    /** A permission sheet: what the site wants, and two clear choices. */
    private fun ask(icon: Int, text: String, done: (Boolean?) -> Unit) {
        val p = a.palette()
        var answered = false
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 4.dp)
            addView(FrameLayout(a).apply {
                background = oval(withAlpha(p.accent, 0.14f))
                addView(a.icon(icon, p.accent, 22).apply { layoutParams = flp(22.dp, 22.dp, Gravity.CENTER) })
            }, lp(48.dp, 48.dp).margins(b = 16.dp))
            addView(a.label(text, 19f, p.text, bold = true, tracking = -0.015f).apply { setLineSpacing(2f.dp, 1f) })
            val buttons = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 24.dp, 0, 0)
            }
            buttons.addView(pillButton(a.getString(R.string.block), p.field, p.text) {
                answered = true; done(false); a.sheet.dismiss()
            }, lp(0, 50.dp, 1f).margins(r = 6.dp))
            buttons.addView(pillButton(a.getString(R.string.allow), p.ink, p.onInk) {
                answered = true; done(true); a.sheet.dismiss()
            }, lp(0, 50.dp, 1f).margins(l = 6.dp))
            addView(buttons)
        }
        a.sheet.onDismiss = { if (!answered) done(null); a.sheet.onDismiss = null }
        a.sheet.show(box)
    }

    private fun pillButton(text: String, bg: Int, fg: Int, onClick: () -> Unit) =
        a.label(text, 15f, fg, bold = true).apply {
            gravity = Gravity.CENTER
            background = ripple(withAlpha(fg, 0.15f), Radius.pill, rounded(bg, Radius.pill))
            isClickable = true
            sinkOnPress()
            setOnClickListener { Haptics.tap(it); onClick() }
        }

    /* ---- Helpers ---------------------------------------------------------------------------- */

    private fun field(value: String, hint: String?) = EditText(a).apply {
        setText(value)
        this.hint = hint
        typeface = Fonts.regular
        isSingleLine = true
    }

    private fun padded(v: android.view.View) = FrameLayout(a).apply {
        setPadding(22.dp, 4.dp, 22.dp, 0)
        addView(v)
    }

    private fun scrollable(v: android.view.View) = android.widget.ScrollView(a).apply {
        isVerticalScrollBarEnabled = false
        addView(v)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }.let { sv ->
        // Cap the sheet height so long <select> lists scroll.
        object : FrameLayout(a) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val max = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
        }.apply { addView(sv) }
    }
}
