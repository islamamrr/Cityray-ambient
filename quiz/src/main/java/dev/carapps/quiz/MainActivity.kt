package dev.carapps.quiz

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.carapps.car.CarDecode
import dev.carapps.car.ParkWatcher
import java.io.File

class MainActivity : Activity() {

    private lateinit var bank: Bank
    private lateinit var online: Online
    private lateinit var sfx: Sfx
    private lateinit var park: ParkWatcher
    private val prefs by lazy { getSharedPreferences("quiz", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var stage: FrameLayout
    private lateinit var parkCover: TextView

    private var lang = Lang.AR
    private var game: Game? = null
    private var blocked = false
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (showLastCrash()) return
        bank = Bank(this).apply { load() }
        online = Online(this)
        sfx = Sfx()
        lang = if (prefs.getString("lang", "ar") == "en") Lang.EN else Lang.AR
        park = ParkWatcher(this) { gear -> main.post { onGear(gear) } }

        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }
        stage = FrameLayout(this)
        parkCover = label("Shift to P to keep playing\n\nحوّل على P عشان تكمل اللعب", 30f, Palette.INK, bold = true).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setBackgroundColor(0xF20E1016.toInt())
            visibility = View.GONE
            isClickable = true // swallow taps
        }
        root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        root.addView(parkCover, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        showSetup()
    }

    // ------------------------------------------------------------------ Crash report

    private val crashFile by lazy { File(filesDir, "crash.txt") }

    /**
     * Saves any crash to a file, and on the next start shows it on screen once, so a photo of
     * the screen is enough to fix it. Returns true when the crash screen is showing.
     */
    private fun showLastCrash(): Boolean {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { crashFile.writeText("Quiz Duel ${BuildConfigInfo.version(this)}\n\n" + Log.getStackTraceString(e)) }
            previous?.uncaughtException(thread, e)
        }
        if (!crashFile.exists()) return false
        val report = runCatching { crashFile.readText() }.getOrDefault("")
        val col = column(gapDp = 16).apply { setPadding(dp(24), dp(32), dp(24), dp(32)) }
        col.addView(label("Quiz Duel crashed last time. Please send a photo of this screen.\nاللعبة وقعت المرة اللي فاتت، ابعت صورة للشاشة دي.", 20f, Palette.GOLD, bold = true), fill())
        col.addView(label(report, 13f, Palette.INK).apply { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }, fill())
        col.addView(tile("Continue / كمل", 22f, Palette.CARD2) {
            crashFile.delete()
            recreate()
        }, fill())
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(col)
        })
        return true
    }

    private fun t(en: String, ar: String) = if (lang == Lang.AR) ar else en

    private fun direction(v: View) {
        v.layoutDirection = if (lang == Lang.AR) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
    }

    // ------------------------------------------------------------------ Park

    /**
     * Park only, like the project's other games. Out of Park the game pauses behind a cover,
     * and on a P -> other shift the app steps aside so the car's normal screen comes back.
     * With no gear reading (a phone) nothing blocks.
     */
    private fun onGear(gear: CarDecode.Gear) {
        val previous = lastGear
        lastGear = gear
        blocked = gear != CarDecode.Gear.P && gear != CarDecode.Gear.UNKNOWN
        parkCover.visibility = if (blocked) View.VISIBLE else View.GONE
        // Only step aside on a real shift out of Park, never on the first reading at launch.
        if (blocked && previous == CarDecode.Gear.P) moveTaskToBack(true)
    }

    private var lastGear = CarDecode.Gear.UNKNOWN

    // ------------------------------------------------------------------ Setup screen

    private val defaultNames = mapOf(
        Lang.EN to listOf("Orange Team", "Blue Team"),
        Lang.AR to listOf("الفريق البرتقاني", "الفريق الأزرق"),
    )

    private fun teamName(i: Int): String {
        val saved = prefs.getString("team$i", null)
        val isDefault = saved == null || defaultNames.values.any { it[i] == saved }
        return if (isDefault) defaultNames.getValue(lang)[i] else saved!!
    }

    private fun showSetup() {
        stopTimer()
        game = null
        stage.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = false }
        val col = column(gapDp = 12).apply { setPadding(dp(24), dp(20), dp(24), dp(24)) }
        direction(col)
        scroll.addView(col)
        // The Start button lives outside the scrolling list, so it is always on screen.
        val startBottom = tile(t("Start", "يلا نبدأ"), 24f, Palette.GOLD, Palette.DARK_TEXT, null)
            .apply { setPadding(dp(16), dp(18), dp(16), dp(18)) }
        val startTop = tile(t("Start", "يلا نبدأ"), 18f, Palette.GOLD, Palette.DARK_TEXT, null)
            .apply { setPadding(dp(22), dp(10), dp(22), dp(10)) }
        val page = column().apply {
            // Keep clear of the car's own bars even if they draw over the app.
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        page.addView(scroll, fillWeight())
        val bar = column().apply { setPadding(dp(24), dp(10), dp(24), dp(20)); setBackgroundColor(Palette.BG) }
        bar.addView(startBottom, fill())
        page.addView(bar, fill())
        direction(page)
        stage.addView(page, FrameLayout.LayoutParams(-1, -1))

        // Title and language
        val head = row(gapDp = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        val titles = column()
        titles.addView(label(t("Quiz Duel", "تحدي الفريقين"), 34f, Palette.INK, bold = true))
        titles.addView(label(t("Two teams, one screen. Fastest right answer wins.", "فريقين، شاشة واحدة، والأسرع يكسب."), 17f, Palette.MUTED))
        head.addView(titles, weight())
        head.addView(startTop, wrap())
        head.addView(tile(if (lang == Lang.AR) "English" else "عربي", 18f, Palette.CARD2) {
            lang = if (lang == Lang.AR) Lang.EN else Lang.AR
            prefs.edit().putString("lang", if (lang == Lang.AR) "ar" else "en").apply()
            showSetup()
        }, wrap())
        col.addView(head, fill())

        // Teams
        val names = (0..1).map { i ->
            EditText(this).apply {
                setText(teamName(i))
                textSize = 22f
                setTextColor(Palette.TEAM[i])
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(Palette.CARD, 16, 2, Palette.TEAM[i])
                setPadding(dp(16), dp(12), dp(16), dp(12))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = EditorInfo.IME_ACTION_DONE
                setSingleLine()
                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            }
        }
        col.addView(section(t("Teams", "الفرق")))
        val teamRow = row(gapDp = 12)
        names.forEach { teamRow.addView(it, weight()) }
        col.addView(teamRow, fill())

        // Mode, length, time
        var mode = if (prefs.getString("mode", "turns") == "buzzer") Mode.BUZZER else Mode.TURNS
        col.addView(section(t("How to play", "طريقة اللعب")))
        col.addView(segmented(
            listOf(t("Take turns", "بالدور"), t("Buzzer race", "مين يضغط الأول")),
            if (mode == Mode.TURNS) 0 else 1,
        ) { mode = if (it == 0) Mode.TURNS else Mode.BUZZER }, fill())
        val modeHelp = label(t(
            "Take turns: each team gets its own question; miss it and the other team can steal it.\nBuzzer race: both teams see the question and the first to buzz answers.",
            "بالدور: كل فريق ليه سؤاله، ولو غلط الفريق التاني يقدر يخطفه.\nمين يضغط الأول: الفريقين شايفين السؤال، واللي يدوس الجرس الأول يجاوب.",
        ), 15f, Palette.MUTED)
        col.addView(modeHelp, fill())

        val lengths = listOf(10, 16, 20)
        var length = prefs.getInt("length", 16).let { if (it in lengths) it else 16 }
        col.addView(section(t("Questions", "عدد الأسئلة")))
        col.addView(segmented(lengths.map { it.toString() }, lengths.indexOf(length)) { length = lengths[it] }, fill())

        val times = listOf(15, 20, 30)
        var seconds = prefs.getInt("seconds", 20).let { if (it in times) it else 20 }
        col.addView(section(t("Seconds per question", "ثواني لكل سؤال")))
        col.addView(segmented(times.map { it.toString() }, times.indexOf(seconds)) { seconds = times[it] }, fill())

        var pick = prefs.getBoolean("pick", true)
        col.addView(section(t("Choosing categories", "اختيار الفئة")))
        col.addView(segmented(
            listOf(t("Team picks each time", "الفريق يختار كل مرة"), t("Random", "عشوائي")),
            if (pick) 0 else 1,
        ) { pick = it == 0 }, fill())

        // Categories
        val chosen = LinkedHashSet(prefs.getStringSet("cats", null) ?: bank.categories.keys)
        col.addView(section(t("Categories", "الفئات")))
        val quick = row(gapDp = 10)
        val chipViews = LinkedHashMap<String, TextView>()
        fun styleChip(id: String) {
            val v = chipViews[id] ?: return
            val on = id in chosen
            v.background = if (on) rounded(Palette.CARD2, 16, 2, Palette.GOLD) else rounded(Palette.CARD, 16)
            v.alpha = if (bank.count(id, lang) == 0) 0.35f else 1f
            v.setTextColor(if (on) Palette.INK else Palette.MUTED)
        }
        quick.addView(tile(t("🇪🇬 Egypt only", "🇪🇬 مصري بس"), 16f, Palette.CARD) {
            chosen.clear(); chosen.addAll(bank.categories.values.filter { it.egypt }.map { it.id })
            chipViews.keys.forEach { styleChip(it) }
        }, weight())
        quick.addView(tile(t("All", "الكل"), 16f, Palette.CARD) {
            chosen.clear(); chosen.addAll(bank.categories.keys)
            chipViews.keys.forEach { styleChip(it) }
        }, weight())
        col.addView(quick, fill())

        val cats = bank.categories.values.toList()
        cats.chunked(3).forEach { pair ->
            val r = row(gapDp = 10)
            pair.forEach { c ->
                val chip = tile("${c.icon}  ${c.name(lang)}\n${bank.count(c.id, lang)} ${t("questions", "سؤال")}", 15f, Palette.CARD) {
                    if (c.id in chosen) chosen.remove(c.id) else chosen.add(c.id)
                    styleChip(c.id)
                }
                chipViews[c.id] = chip
                styleChip(c.id)
                r.addView(chip, weight())
            }
            repeat(3 - pair.size) { r.addView(View(this), weight()) }
            col.addView(r, fill())
        }

        // Online
        col.addView(section(t("New questions", "أسئلة جديدة")))
        val onlineStatus = label(
            prefs.getString("onlineNote_${lang.name}", null)
                ?: t("Downloads Arabic and Egyptian packs from the project, plus English questions from Open Trivia DB.",
                    "بينزل باقات أسئلة عربي ومصري من المشروع، وكمان أسئلة إنجليزي من Open Trivia DB."),
            15f, Palette.MUTED,
        )
        val fetch = tile(t("⬇  Get new questions from the internet", "⬇  هات أسئلة جديدة من النت"), 18f, Palette.CARD2) {
            if (online.running) return@tile
            online.refresh(lang, progress = { onlineStatus.text = it }) { added, error ->
                bank.load()
                val note = when {
                    added > 0 -> t("Added $added new questions.", "اتضاف $added سؤال جديد.") +
                        if (lang == Lang.AR) " (أسئلة Open Trivia DB إنجليزي بس، بتظهر في وضع English.)" else ""
                    error != null -> t("Couldn't download: $error. Is the car online?", "مقدرناش ننزل: $error. العربية متوصلة بالنت؟")
                    else -> t("No new questions right now.", "مفيش أسئلة جديدة دلوقتي.")
                }
                prefs.edit().putString("onlineNote_${lang.name}", note).apply()
                if (game == null) showSetup()
            }
        }
        col.addView(fetch, fill())
        col.addView(onlineStatus, fill())

        // Start (both buttons do the same)
        val go = go@{
            val playable = bank.available(chosen, lang)
            if (playable.isEmpty()) {
                Toast.makeText(this, t("Pick at least one category with questions.", "اختار فئة واحدة على الأقل فيها أسئلة."), Toast.LENGTH_LONG).show()
                return@go
            }
            val teamNames = names.mapIndexed { i, e -> e.text.toString().trim().ifEmpty { defaultNames.getValue(lang)[i] } }
            prefs.edit()
                .putString("team0", teamNames[0]).putString("team1", teamNames[1])
                .putString("mode", if (mode == Mode.BUZZER) "buzzer" else "turns")
                .putInt("length", length).putInt("seconds", seconds).putBoolean("pick", pick)
                .putStringSet("cats", HashSet(chosen))
                .apply()
            startGame(Settings(lang, mode, length, seconds, playable, pick, teamNames))
        }
        listOf(startBottom, startTop).forEach { b ->
            b.isClickable = true
            b.setOnClickListener { go() }
        }
    }

    private fun section(text: String) = label(text.uppercase(), 15f, Palette.GOLD, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(0, dp(10), 0, 0)
    }

    /** A row of options where exactly one is on. */
    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val r = row(gapDp = 10)
        val views = ArrayList<TextView>()
        fun style(sel: Int) = views.forEachIndexed { i, v ->
            v.background = if (i == sel) rounded(Palette.CARD2, 16, 2, Palette.GOLD) else rounded(Palette.CARD, 16)
            v.setTextColor(if (i == sel) Palette.INK else Palette.MUTED)
        }
        options.forEachIndexed { i, o ->
            val v = tile(o, 18f, Palette.CARD) { style(i); onSelect(i) }
            views.add(v)
            r.addView(v, weight())
        }
        style(selected)
        return r
    }

    // ------------------------------------------------------------------ Game screen

    private lateinit var scoreCards: List<LinearLayout>
    private lateinit var scoreNames: List<TextView>
    private lateinit var scoreValues: List<TextView>
    private lateinit var scoreInfo: List<TextView>
    private lateinit var fiftyButtons: List<TextView>
    private lateinit var progressText: TextView
    private lateinit var goldenBadge: TextView
    private lateinit var questionPanel: LinearLayout
    private lateinit var pickPanel: LinearLayout
    private lateinit var pickTitle: TextView
    private lateinit var pickButtons: List<TextView>
    private lateinit var categoryText: TextView
    private lateinit var timerBar: TimerBar
    private lateinit var questionText: TextView
    private lateinit var answerViews: List<TextView>
    private lateinit var statusText: TextView
    private lateinit var buzzRow: LinearLayout
    private lateinit var nextButton: TextView

    private fun startGame(settings: Settings) {
        val g = Game(settings, bank)
        game = g
        buildGameScreen(g)
        g.begin()
        afterChange()
    }

    private fun buildGameScreen(g: Game) {
        stage.removeAllViews()
        val col = column(gapDp = 14).apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        direction(col)
        stage.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Top bar
        val top = row(gapDp = 12).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(tile("✕", 20f, Palette.CARD) { confirmQuit() }, wrap())
        progressText = label("", 18f, Palette.MUTED, bold = true)
        top.addView(progressText, weight())
        goldenBadge = tile(t("★ Double points", "★ النقط دبل"), 16f, Palette.GOLD, Palette.DARK_TEXT)
        top.addView(goldenBadge, wrap())
        col.addView(top, fill())

        // Scores
        val scores = row(gapDp = 12)
        val cards = ArrayList<LinearLayout>(); val nm = ArrayList<TextView>(); val sv = ArrayList<TextView>()
        val info = ArrayList<TextView>(); val fifty = ArrayList<TextView>()
        for (i in 0..1) {
            val card = column(gapDp = 4).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
            val n = label(g.teams[i].name, 18f, Palette.TEAM[i], bold = true).apply { maxLines = 1 }
            val s = label("0", 40f, Palette.INK, bold = true)
            val bottom = row(gapDp = 8).apply { gravity = Gravity.CENTER_VERTICAL }
            val inf = label("", 16f, Palette.MUTED)
            val f = tile("50:50", 15f, Palette.CARD2) { onFifty(i) }.apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
            bottom.addView(inf, weight())
            bottom.addView(f, wrap())
            card.addView(n, fill()); card.addView(s, fill()); card.addView(bottom, fill())
            cards.add(card); nm.add(n); sv.add(s); info.add(inf); fifty.add(f)
            scores.addView(card, weight())
        }
        scoreCards = cards; scoreNames = nm; scoreValues = sv; scoreInfo = info; fiftyButtons = fifty
        col.addView(scores, fill())

        // Middle: either the category pick or the question
        val middle = FrameLayout(this)
        col.addView(middle, fillWeight())

        questionPanel = column(gapDp = 14)
        categoryText = label("", 18f, Palette.MUTED, bold = true)
        timerBar = TimerBar(this)
        questionText = label("", 30f, Palette.INK, bold = true).apply { setLineSpacing(0f, 1.15f) }
        questionPanel.addView(categoryText, fill())
        questionPanel.addView(timerBar, LinearLayout.LayoutParams(-1, dp(12)))
        val qBox = FrameLayout(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        qBox.addView(questionText, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        questionPanel.addView(qBox, fillWeight())
        answerViews = (0 until 4).map { i ->
            tile("", 22f, Palette.CARD) { onAnswer(i) }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                minHeight = dp(76)
                setPadding(dp(22), dp(16), dp(22), dp(16))
            }.also { questionPanel.addView(it, fill()) }
        }
        statusText = label("", 20f, Palette.INK, bold = true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
        }
        questionPanel.addView(statusText, fill())
        middle.addView(questionPanel, FrameLayout.LayoutParams(-1, -1))

        pickPanel = column(gapDp = 16).apply { gravity = Gravity.CENTER_VERTICAL }
        pickTitle = label("", 28f, Palette.INK, bold = true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }
        pickPanel.addView(pickTitle, fill())
        pickButtons = (0 until 3).map { i ->
            tile("", 26f, Palette.CARD) { onPick(i) }.apply { minHeight = dp(110) }.also { pickPanel.addView(it, fill()) }
        }
        middle.addView(pickPanel, FrameLayout.LayoutParams(-1, -1))

        // Bottom
        buzzRow = row(gapDp = 12)
        for (i in 0..1) {
            buzzRow.addView(tile("🔔 " + g.teams[i].name, 26f, Palette.TEAM[i], Palette.DARK_TEXT) { onBuzz(i) }.apply {
                minHeight = dp(120); maxLines = 2
            }, weight())
        }
        col.addView(buzzRow, fill())
        nextButton = tile("", 24f, Palette.GOLD, Palette.DARK_TEXT) { onNext() }.apply { minHeight = dp(84) }
        col.addView(nextButton, fill())
    }

    private fun render() {
        val g = game ?: return
        val s = g.settings
        for (i in 0..1) {
            val team = g.teams[i]
            val active = when (g.phase) {
                Game.Phase.PICK -> g.turnTeam == i
                Game.Phase.ANSWER, Game.Phase.STEAL -> g.answering == i
                else -> false
            }
            scoreCards[i].background = if (active) rounded(Palette.CARD2, 20, 3, Palette.TEAM[i]) else rounded(Palette.CARD, 20)
            scoreValues[i].text = team.score.toString()
            val gain = if (g.phase == Game.Phase.REVEAL && g.lastGainTeam == i) "+${g.lastGain}  " else ""
            val streak = if (team.streak >= 2) "🔥${team.streak}" else ""
            scoreInfo[i].text = gain + streak
            scoreInfo[i].setTextColor(if (gain.isNotEmpty()) Palette.RIGHT else Palette.MUTED)
            fiftyButtons[i].visibility = if (team.fiftyUsed) View.INVISIBLE else View.VISIBLE
            fiftyButtons[i].alpha = if (g.canFifty(i)) 1f else 0.35f
        }

        progressText.text = if (g.isTiebreak) t("Tiebreaker!", "سؤال الحسم!")
            else t("Question ${g.index + 1} of ${s.questions}", "سؤال ${g.index + 1} من ${s.questions}")
        goldenBadge.visibility = if (g.isGolden) View.VISIBLE else View.GONE

        val picking = g.phase == Game.Phase.PICK
        pickPanel.visibility = if (picking) View.VISIBLE else View.GONE
        questionPanel.visibility = if (picking) View.GONE else View.VISIBLE
        if (picking) {
            val team = g.teams[g.turnTeam]
            pickTitle.text = t("${team.name}, pick a category", "${team.name}، اختار الفئة")
            pickTitle.setTextColor(Palette.TEAM[g.turnTeam])
            pickButtons.forEachIndexed { i, b ->
                val c = g.offered.getOrNull(i)
                b.visibility = if (c == null) View.GONE else View.VISIBLE
                if (c != null) b.text = "${c.icon}  ${c.name(lang)}"
            }
        } else {
            val c = g.category
            categoryText.text = if (c == null) "" else "${c.icon}  ${c.name(lang)}"
            questionText.text = g.question?.inLang(s.lang)?.question ?: ""
            val reveal = g.phase == Game.Phase.REVEAL
            val canTap = g.phase == Game.Phase.ANSWER || g.phase == Game.Phase.STEAL
            answerViews.forEachIndexed { i, v ->
                val text = g.options.getOrNull(i) ?: ""
                val isRight = i == g.correctIndex
                val gone = i in g.removed
                when {
                    reveal && isRight -> { v.text = "✓  $text"; v.background = rounded(Palette.RIGHT); v.setTextColor(Palette.DARK_TEXT); v.alpha = 1f }
                    i == g.pickedIndex && !isRight -> {
                        v.text = "✗  $text"; v.background = rounded(Palette.WRONG); v.setTextColor(Palette.INK); v.alpha = 1f
                    }
                    gone -> { v.text = text; v.background = rounded(Palette.CARD); v.setTextColor(Palette.MUTED); v.alpha = 0.3f }
                    else -> {
                        v.text = text
                        val tint = if (canTap && g.answering >= 0) Palette.TEAM[g.answering] else Palette.LINE
                        v.background = rounded(Palette.CARD, 18, 2, tint)
                        v.setTextColor(Palette.INK)
                        v.alpha = if (canTap || reveal) 1f else 0.7f
                    }
                }
                v.isEnabled = canTap && !gone
            }
            val mult = if (g.isGolden) 2 else 1
            val answeringName = g.teams.getOrNull(g.answering)?.name ?: ""
            statusText.text = when (g.phase) {
                Game.Phase.OPEN -> t("Buzz in! First team to buzz answers.", "دوس الجرس! أول فريق يدوس يجاوب.")
                Game.Phase.ANSWER -> if (g.mode == Mode.BUZZER) t("$answeringName buzzed! 10 seconds.", "$answeringName داس الجرس! معاك 10 ثواني.")
                    else t("$answeringName's question", "سؤال $answeringName")
                Game.Phase.STEAL -> t("Steal! $answeringName, 10 seconds for ${50 * mult} points", "اخطف! $answeringName، معاك 10 ثواني على ${50 * mult} نقطة")
                Game.Phase.REVEAL -> if (g.lastGainTeam >= 0) t("${g.teams[g.lastGainTeam].name} +${g.lastGain}", "${g.teams[g.lastGainTeam].name} +${g.lastGain}")
                    else t("Nobody got it!", "محدش عرفها!")
                else -> ""
            }
            statusText.setTextColor(when {
                g.phase == Game.Phase.REVEAL && g.lastGainTeam >= 0 -> Palette.TEAM[g.lastGainTeam]
                g.answering >= 0 && (g.phase == Game.Phase.ANSWER || g.phase == Game.Phase.STEAL) -> Palette.TEAM[g.answering]
                else -> Palette.INK
            })
            timerBar.visibility = if (canTap || g.phase == Game.Phase.OPEN) View.VISIBLE else View.INVISIBLE
            timerBar.color = if (g.answering >= 0 && canTap) Palette.TEAM[g.answering] else Palette.GOLD
        }

        buzzRow.visibility = if (g.phase == Game.Phase.OPEN) View.VISIBLE else View.GONE
        nextButton.visibility = if (g.phase == Game.Phase.REVEAL) View.VISIBLE else View.GONE
        val last = g.index + 1 >= s.questions && g.teams[0].score != g.teams[1].score
        nextButton.text = if (last) t("See the results", "شوف النتيجة") else t("Next question", "السؤال اللي بعده")
    }

    // ------------------------------------------------------------------ Actions

    private fun onPick(i: Int) {
        val g = game ?: return
        if (g.phase != Game.Phase.PICK || blocked) return
        val c = g.offered.getOrNull(i) ?: return
        g.choose(c)
        afterChange()
    }

    private fun onBuzz(team: Int) {
        val g = game ?: return
        if (blocked || !g.buzz(team)) return
        sfx.buzz()
        afterChange()
    }

    private fun onAnswer(option: Int) {
        val g = game ?: return
        if (blocked) return
        val right = g.answer(option, fractionLeft())
        if (g.pickedIndex != option) return
        if (right) sfx.right() else sfx.wrong()
        afterChange()
    }

    private fun onFifty(team: Int) {
        val g = game ?: return
        if (blocked || !g.fifty(team)) return
        render()
    }

    private fun onNext() {
        val g = game ?: return
        if (blocked) return
        g.next()
        afterChange()
    }

    /** Called after every change of phase: restart the clock if needed, then redraw. */
    private fun afterChange() {
        val g = game ?: return
        if (g.phase == Game.Phase.OVER) {
            stopTimer()
            showResults(g)
            return
        }
        val seconds = g.timeFor(g.phase)
        if (seconds > 0) startTimer(seconds) else stopTimer()
        render()
    }

    private fun confirmQuit() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(t("End this game?", "تنهي اللعبة؟"))
            .setPositiveButton(t("End game", "أنهي")) { _, _ -> showSetup() }
            .setNegativeButton(t("Keep playing", "كمل"), null)
            .show()
    }

    // ------------------------------------------------------------------ Timer

    private var timerTotal = 0L
    private var timerLeft = 0L
    private var lastTick = 0L
    private var timerOn = false
    private var lastBeep = -1

    private fun startTimer(seconds: Int) {
        timerTotal = seconds * 1000L
        timerLeft = timerTotal
        lastTick = SystemClock.uptimeMillis()
        lastBeep = -1
        if (!timerOn) {
            timerOn = true
            main.post(ticker)
        }
        timerBar.fraction = 1f
    }

    private fun stopTimer() {
        timerOn = false
        main.removeCallbacks(ticker)
    }

    private fun fractionLeft(): Float = if (timerTotal <= 0) 0f else timerLeft.toFloat() / timerTotal

    private val ticker = object : Runnable {
        override fun run() {
            if (!timerOn) return
            val now = SystemClock.uptimeMillis()
            val dt = now - lastTick
            lastTick = now
            if (!blocked && resumed) {
                timerLeft -= dt
                val secs = ((timerLeft + 999) / 1000).toInt()
                if (secs in 1..5 && secs != lastBeep) { lastBeep = secs; sfx.tick() }
                timerBar.fraction = fractionLeft()
                if (timerLeft <= 0) {
                    timerOn = false
                    val g = game
                    if (g != null) {
                        g.timeout()
                        sfx.wrong()
                        afterChange()
                    }
                    return
                }
            }
            main.postDelayed(this, 50)
        }
    }

    // ------------------------------------------------------------------ Results

    private fun showResults(g: Game) {
        stage.removeAllViews()
        val col = column(gapDp = 20).apply {
            setPadding(dp(24), dp(48), dp(24), dp(32))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        direction(col)
        stage.addView(ScrollView(this).apply { isFillViewport = true; addView(col) }, FrameLayout.LayoutParams(-1, -1))
        val w = g.winner()
        sfx.win()
        col.addView(label("🏆", 96f).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }, fill())
        col.addView(label(
            if (w < 0) t("It's a draw!", "تعادل!") else t("${g.teams[w].name} wins!", "${g.teams[w].name} كسب!"),
            40f, if (w < 0) Palette.GOLD else Palette.TEAM[w], bold = true,
        ).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER }, fill())

        val cards = row(gapDp = 12)
        for (i in 0..1) {
            val team = g.teams[i]
            val card = column(gapDp = 6).apply {
                setPadding(dp(20), dp(20), dp(20), dp(20))
                background = rounded(Palette.CARD, 20, if (i == w) 3 else 0, Palette.TEAM[i])
            }
            card.addView(label(team.name, 20f, Palette.TEAM[i], bold = true), fill())
            card.addView(label(team.score.toString(), 52f, Palette.INK, bold = true), fill())
            card.addView(label(t("${team.correct} right answers", "${team.correct} إجابة صح"), 17f, Palette.MUTED), fill())
            card.addView(label(t("Best streak: ${team.bestStreak}", "أطول سلسلة: ${team.bestStreak}"), 17f, Palette.MUTED), fill())
            cards.addView(card, weight())
        }
        col.addView(cards, fill())
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        col.addView(tile(t("Rematch", "ماتش العودة"), 24f, Palette.GOLD, Palette.DARK_TEXT) {
            startGame(g.settings)
        }.apply { minHeight = dp(84) }, fill())
        col.addView(tile(t("New game", "لعبة جديدة"), 22f, Palette.CARD2) { showSetup() }.apply { minHeight = dp(76) }, fill())
    }

    // ------------------------------------------------------------------ Lifecycle

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        if (!::park.isInitialized) return
        park.start()
        lastTick = SystemClock.uptimeMillis()
    }

    override fun onPause() {
        resumed = false
        if (!::park.isInitialized) { super.onPause(); return }
        park.stop()
        lastGear = CarDecode.Gear.UNKNOWN
        blocked = false
        parkCover.visibility = View.GONE
        super.onPause()
    }

    override fun onDestroy() {
        stopTimer()
        if (::sfx.isInitialized) sfx.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (game != null) confirmQuit() else super.onBackPressed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
}
