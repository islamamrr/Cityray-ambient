package dev.carapps.quiz

enum class Mode { TURNS, BUZZER }

class Settings(
    val lang: Lang,
    val mode: Mode,
    val questions: Int,
    val seconds: Int,
    val categories: List<String>,
    val pickCategory: Boolean,
    val teamNames: List<String>,
)

class Team(val name: String) {
    var score = 0
    var correct = 0
    var streak = 0
    var bestStreak = 0
    var fiftyUsed = false
}

/**
 * The rules, kept apart from the screen.
 *
 * Take turns: questions alternate between the teams. A wrong answer or running out of time
 * gives the other team a 10-second steal for 50 points.
 * Buzzer race: both teams see the question; the first to buzz answers. A wrong answer hands
 * the other team a steal.
 * Right answer: 100 points plus up to 50 for speed. The last two questions count double.
 * Each team has one 50:50 per game. A tie at the end goes to buzzer tiebreakers.
 */
class Game(val settings: Settings, private val bank: Bank) {

    enum class Phase { PICK, OPEN, ANSWER, STEAL, REVEAL, OVER }

    val teams = settings.teamNames.map { Team(it) }
    var index = 0 // 0-based number of the current question
        private set
    var tiebreaks = 0
        private set
    var phase = Phase.PICK
        private set

    var question: Question? = null
        private set
    var category: Category? = null
        private set
    /** Answers in screen order. */
    var options: List<String> = emptyList()
        private set
    var correctIndex = -1
        private set
    /** Options knocked out by a wrong answer or a 50:50. */
    val removed = HashSet<Int>()
    /** The team allowed to tap an answer now, or -1. */
    var answering = -1
        private set
    var pickedIndex = -1
        private set
    var lastGain = 0
        private set
    var lastGainTeam = -1
        private set

    /** Categories offered in the current PICK phase. */
    var offered: List<Category> = emptyList()
        private set

    private val asked = HashSet<String>()

    val isTiebreak get() = index >= settings.questions
    val isGolden get() = !isTiebreak && settings.questions >= 6 && index >= settings.questions - 2
    val mode get() = if (isTiebreak) Mode.BUZZER else settings.mode
    /** Whose turn it is in take-turns mode, and who picks the category. */
    val turnTeam get() = index % 2

    fun timeFor(p: Phase): Int = when (p) {
        Phase.OPEN, Phase.ANSWER -> if (mode == Mode.BUZZER && p == Phase.ANSWER) 10 else settings.seconds
        Phase.STEAL -> 10
        else -> 0
    }

    /** Starts the next question: a category pick, or straight to the question. */
    fun begin() {
        val ids = bank.available(settings.categories, settings.lang)
        val cats = ids.mapNotNull { bank.categories[it] }
        if (cats.isEmpty()) { phase = Phase.OVER; return }
        if (settings.pickCategory && !isTiebreak && cats.size > 1) {
            offered = cats.shuffled().take(3)
            phase = Phase.PICK
        } else {
            choose(cats.random())
        }
    }

    fun choose(c: Category) {
        category = c
        val q = bank.draw(c.id, settings.lang, asked)
            ?: bank.available(settings.categories, settings.lang).asSequence()
                .mapNotNull { id -> bank.draw(id, settings.lang, asked)?.also { category = bank.categories[id] } }
                .firstOrNull()
        if (q == null) { phase = Phase.OVER; return }
        asked.add(q.key)
        question = q
        val p = q.inLang(settings.lang)!!
        val all = (listOf(p.correct) + p.wrong).shuffled()
        options = all
        correctIndex = all.indexOf(p.correct)
        removed.clear()
        pickedIndex = -1
        lastGain = 0
        lastGainTeam = -1
        if (mode == Mode.TURNS) {
            answering = turnTeam
            phase = Phase.ANSWER
        } else {
            answering = -1
            phase = Phase.OPEN
        }
    }

    /** Buzzer mode: a team claims the question. */
    fun buzz(team: Int): Boolean {
        if (phase != Phase.OPEN) return false
        answering = team
        phase = Phase.ANSWER
        return true
    }

    /** Returns true if the answer was right. [fraction] is the share of time left, 0..1. */
    fun answer(option: Int, fraction: Float): Boolean {
        if (phase != Phase.ANSWER && phase != Phase.STEAL) return false
        if (option in removed) return false
        pickedIndex = option
        val team = teams[answering]
        val mult = if (isGolden) 2 else 1
        return if (option == correctIndex) {
            val gain = if (phase == Phase.STEAL) 50 * mult else (100 + Math.round(50 * fraction.coerceIn(0f, 1f))) * mult
            award(answering, gain)
            phase = Phase.REVEAL
            true
        } else {
            team.streak = 0
            removed.add(option)
            failOver()
            false
        }
    }

    /** The clock ran out for whoever was meant to act. */
    fun timeout() {
        when (phase) {
            Phase.OPEN -> reveal()
            Phase.ANSWER -> { teams[answering].streak = 0; failOver() }
            Phase.STEAL -> { teams[answering].streak = 0; reveal() }
            else -> {}
        }
    }

    /** One 50:50 per team per game, only on its own main answer. */
    fun canFifty(team: Int) = phase == Phase.ANSWER && answering == team && !teams[team].fiftyUsed && removed.isEmpty()

    fun fifty(team: Int): Boolean {
        if (!canFifty(team)) return false
        teams[team].fiftyUsed = true
        options.indices.filter { it != correctIndex }.shuffled().take(2).forEach { removed.add(it) }
        return true
    }

    /** After a reveal: next question, or the end. */
    fun next() {
        index++
        if (index >= settings.questions) {
            val tied = teams[0].score == teams[1].score
            if (!tied || tiebreaks >= 5) { phase = Phase.OVER; return }
            tiebreaks++
        }
        begin()
    }

    fun winner(): Int = when {
        teams[0].score > teams[1].score -> 0
        teams[1].score > teams[0].score -> 1
        else -> -1
    }

    private fun failOver() {
        if (phase == Phase.ANSWER) {
            answering = 1 - answering
            phase = Phase.STEAL
        } else {
            reveal()
        }
    }

    private fun reveal() {
        phase = Phase.REVEAL
    }

    private fun award(team: Int, gain: Int) {
        val t = teams[team]
        t.score += gain
        t.correct++
        t.streak++
        t.bestStreak = maxOf(t.bestStreak, t.streak)
        lastGain = gain
        lastGainTeam = team
    }
}
