package questJournal

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest

// questjournal 2.0 - список активных квестов, цели, маркеры, подсказки

enum class QuestStatus {
    ACTIVE,
    COMPLETED,
    FAILED
}

enum class QuestMarker {
    NEW,
    PINNED,
    COMPLETED,
    NONE
}

data class QuestJournalEntry(
    val questId: String,
    val title: String,
    val status: QuestStatus,
    val objectiveText: String,  // Исправлено: было objectiveQuest
    val marker: QuestMarker,
    val markerHint: String
)

// ------------------- СОБЫТИЯ

sealed interface GameEvent {
    val playerId: String
}

data class QuestJournalUpdated(
    override val playerId: String
) : GameEvent

data class QuestOpened(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestPinned(
    override val playerId: String,
    val questId: String
) : GameEvent

data class QuestProgressed(  // Исправлено: было QuestProggressed
    override val playerId: String,
    val questId: String
) : GameEvent

// ------------- Команды

sealed interface GameCommand {
    val playerId: String
}

data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdPinQuest(
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdProgressQuest(  // Исправлено: было CmdProggressQuest
    override val playerId: String,
    val questId: String
) : GameCommand

data class CmdSwitchPlayer(
    override val playerId: String,
    val newPlayerId: String  // Исправлено: добавил правильное поле
) : GameCommand

data class CmdAddQuest(  // Добавлен недостающий класс
    override val playerId: String,
    val questId: String,
    val title: String
) : GameCommand

// ------------ Серверные данные

data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val step: Int,
    val status: QuestStatus,
    val isNew: Boolean,
    val isPinned: Boolean
)

class QuestSystem {
    private fun objectiveFor(questId: String, step: Int): String {
        return when (questId) {
            "q_alchemist" -> when (step) {
                0 -> "Поговорить с алхимиком"
                1 -> "Собери траву"
                2 -> "Принеси траву"
                else -> "Квест завершён"
            }
            "q_guard" -> when (step) {
                0 -> "Поговорить со стражем этой двери"
                1 -> "Заплатить 10 золота"
                else -> "Проход открыт"
            }
            "q_blacksmith" -> when (step) {
                0 -> "Найти кузнеца"
                1 -> "Принести 5 железных слитков"
                2 -> "Получить награду"
                else -> "Квест завершён"
            }
            "q_herbalist" -> when (step) {
                0 -> "Поговорить с травницей"
                1 -> "Собрать 3 целебных корня"
                2 -> "Вернуться к травнице"
                else -> "Квест завершён"
            }
            else -> "Неизвестный квест"
        }
    }

    private fun markerHintFor(questId: String, step: Int): String {
        return when (questId) {
            "q_alchemist" -> when (step) {
                0 -> "Идти к NPC: Алхимик"
                1 -> "Собрать herb x2"
                2 -> "Вернись к NPC"
                else -> "Готово"
            }
            "q_guard" -> when (step) {
                0 -> "Идти к NPC: Страж"
                1 -> "Найти чем расплатиться со стражником"
                else -> "Готово"
            }
            "q_blacksmith" -> when (step) {
                0 -> "Идти к кузнице в центре города"
                1 -> "Добыть железную руду в шахте"
                2 -> "Вернуться к кузнецу"
                else -> "Готово"
            }
            "q_herbalist" -> when (step) {
                0 -> "Идти к дому травницы на окраине"
                1 -> "Искать целебные корни в лесу"
                2 -> "Вернуться к травнице"
                else -> "Готово"
            }
            else -> ""
        }
    }

    fun toJournalEntry(quest: QuestStateOnServer): QuestJournalEntry {
        val objective = objectiveFor(quest.questId, quest.step)
        val hint = markerHintFor(quest.questId, quest.step)

        val marker = when {
            quest.status == QuestStatus.COMPLETED -> QuestMarker.COMPLETED
            quest.isPinned -> QuestMarker.PINNED  
            quest.isNew -> QuestMarker.NEW
            else -> QuestMarker.NONE
        }
        return QuestJournalEntry(
            quest.questId,
            quest.title,
            quest.status,
            objective,
            marker,
            hint
        )
    }
}

class GameServer {
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean {
        return _commands.tryEmit(cmd)
    }

    private val _questByPlayer = MutableStateFlow<Map<String, List<QuestStateOnServer>>>(
        mapOf(
            "Oleg" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, true),
                QuestStateOnServer("q_guard", "Тебе сюды нельзя", 0, QuestStatus.ACTIVE, true, false)
            ),
            "Stas" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, true),
                QuestStateOnServer("q_guard", "Тебе сюды нельзя", 0, QuestStatus.ACTIVE, true, false)
            )
        )
    )
    val questByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questByPlayer.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            commands.collect { cmd ->
                process(cmd)
            }
        }
    }

    private suspend fun process(cmd: GameCommand) {
        when (cmd) {
            is CmdOpenQuest -> openQuest(cmd.playerId, cmd.questId)
            is CmdPinQuest -> pinQuest(cmd.playerId, cmd.questId)
            is CmdProgressQuest -> progressQuest(cmd.playerId, cmd.questId)
            is CmdAddQuest -> addQuest(cmd.playerId, cmd.questId, cmd.title)
            is CmdSwitchPlayer -> {}
        }
    }

    private fun getPlayerQuests(playerId: String): List<QuestStateOnServer> {
        return _questByPlayer.value[playerId] ?: emptyList()
    }

    private fun setPlayerQuests(playerId: String, quests: List<QuestStateOnServer>) {
        val oldMap = _questByPlayer.value.toMutableMap()
        oldMap[playerId] = quests
        _questByPlayer.value = oldMap
    }

    private suspend fun openQuest(playerId: String, questId: String) {
        val quests = getPlayerQuests(playerId).toMutableList()
        var changed = false

        for (i in quests.indices) {
            val q = quests[i]
            if (q.questId == questId && q.isNew) {
                quests[i] = q.copy(isNew = false)
                changed = true
            }
        }

        if (changed) {
            setPlayerQuests(playerId, quests)
            _events.emit(QuestJournalUpdated(playerId))
        }
    }

    private suspend fun pinQuest(playerId: String, questId: String) {
        val quests = getPlayerQuests(playerId).toMutableList()
        var changed = false

        for (i in quests.indices) {
            val q = quests[i]
            val newPinned = q.questId == questId
            if (q.isPinned != newPinned) {
                quests[i] = q.copy(isPinned = newPinned)
                changed = true
            }
        }

        if (changed) {
            setPlayerQuests(playerId, quests)
            _events.emit(QuestJournalUpdated(playerId))
        }
    }

    private suspend fun progressQuest(playerId: String, questId: String) {
        val quests = getPlayerQuests(playerId).toMutableList()
        var changed = false

        for (i in quests.indices) {
            val q = quests[i]
            if (q.questId == questId && q.status != QuestStatus.COMPLETED) {
                val newStep = q.step + 1
                val completed = when (q.questId) {
                    "q_alchemist" -> newStep >= 3
                    "q_guard" -> newStep >= 2
                    "q_blacksmith" -> newStep >= 3
                    "q_herbalist" -> newStep >= 3
                    else -> false
                }
                val newStatus = if (completed) QuestStatus.COMPLETED else QuestStatus.ACTIVE
                quests[i] = q.copy(step = newStep, status = newStatus, isNew = false)
                changed = true
            }
        }

        if (changed) {
            setPlayerQuests(playerId, quests)
            _events.emit(QuestJournalUpdated(playerId))
        }
    }

    private suspend fun addQuest(playerId: String, questId: String, title: String) {
        val quests = getPlayerQuests(playerId).toMutableList()

        if (quests.none { it.questId == questId }) {
            val newQuest = QuestStateOnServer(
                questId = questId,
                title = title,
                step = 0,
                status = QuestStatus.ACTIVE,
                isNew = true,
                isPinned = false
            )
            quests.add(newQuest)
            setPlayerQuests(playerId, quests)
            _events.emit(QuestJournalUpdated(playerId))
        }
    }
}

class HudState {
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")

    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
    val selectedQuestId = mutableStateOf<String?>(null)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, text: String) {
    hud.log.value = (hud.log.value + text).takeLast(20)
}

fun markerSymbol(m: QuestMarker): String {
    return when (m) {
        QuestMarker.NEW -> "!"
        QuestMarker.PINNED -> "-"
        QuestMarker.COMPLETED -> "✓"
        QuestMarker.NONE -> "о"
    }
}

fun sortQuestEntries(entries: List<QuestJournalEntry>): List<QuestJournalEntry> {
    val order = mapOf(
        QuestMarker.PINNED to 0,
        QuestMarker.NEW to 1,
        QuestMarker.NONE to 2,
        QuestMarker.COMPLETED to 3
    )

    return entries.sortedWith(
        compareBy(
            { order[it.marker] ?: 4 },
            { it.title }
        )
    )
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()
    val quests = QuestSystem()

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.7f)
                roughness(0.4f)
            }
            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }
        server.start(coroutineScope)
    }

    addScene {
        setupUiScene(ClearColorLoad)

        coroutineScope.launch {
            server.questByPlayer.collect { map ->
                val pid = hud.activePlayerIdFlow.value
                val serverList = map[pid] ?: emptyList()

                val entries = serverList.map { quests.toJournalEntry(it) }
                hud.questEntries.value = sortQuestEntries(entries)

                if (hud.selectedQuestId.value == null) {
                    val pinned = entries.firstOrNull { it.marker == QuestMarker.PINNED }
                    if (pinned != null) hud.selectedQuestId.value = pinned.questId
                }
            }
        }

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter { it.playerId == pid }
            }
            .map { e -> "[${e.playerId}] ${e::class.simpleName}" }
            .onEach { line -> hudLog(hud, line) }
            .launchIn(coroutineScope)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Player: ${hud.activePlayerIdUi.use()}") {
                    modifier.margin(bottom = 8.dp)
                }


                Row {
                    Button("Switch Player") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdFlow.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdFlow.value = newId
                            hud.activePlayerIdUi.value = newId
                            hud.selectedQuestId.value = null
                        }
                    }

                    Button("Add Quest") {
                        modifier.onClick {
                            val newQuestId = when ((1..4).random()) {
                                1 -> "q_alchemist"
                                2 -> "q_guard"
                                3 -> "q_blacksmith"
                                else -> "q_herbalist"
                            }

                            val newQuestTitle = when (newQuestId) {
                                "q_alchemist" -> "Алхимик и трава"
                                "q_guard" -> "Тебе сюды нельзя"
                                "q_blacksmith" -> "Кузнечное дело"
                                "q_herbalist" -> "Травница"
                                else -> "Новый квест"
                            }

                            server.trySend(CmdAddQuest(
                                playerId = hud.activePlayerIdUi.value,
                                questId = newQuestId,
                                title = newQuestTitle
                            ))

                            hudLog(hud, "Added quest: $newQuestTitle")
                        }
                    }
                }

                Text("Активные квесты:") {}

                val entries = hud.questEntries.use()
                val selectedId = hud.selectedQuestId.use()

                for (q in entries) {
                    val symbol = markerSymbol(q.marker)

                    val line = "$symbol ${q.title}"
                    Button(line) {
                        modifier
                            .margin(bottom = 4.dp)
                            .onClick {
                                hud.selectedQuestId.value = q.questId
                                if (q.marker == QuestMarker.NEW) {
                                    server.trySend(CmdOpenQuest(hud.activePlayerIdUi.value, q.questId))
                                }
                            }
                    }

                    Text(" - ${q.objectiveText}") {}

                    if (selectedId == q.questId) {
                        Text("Подсказка: ${q.markerHint}") {}
                        Row {
                            Button("Pin") {
                                modifier.margin(end = 8.dp).onClick {
                                    server.trySend(CmdPinQuest(hud.activePlayerIdUi.value, q.questId))
                                }
                            }
                            Button("Progress") {
                                modifier.margin(end = 8.dp).onClick {
                                    server.trySend(CmdProgressQuest(hud.activePlayerIdUi.value, q.questId))
                                }
                            }
                        }
                    }
                }

                Text("Log:") {}
                for (line in hud.log.use()) {
                    Text(line) {}
                }
            }
        }
    }
}
