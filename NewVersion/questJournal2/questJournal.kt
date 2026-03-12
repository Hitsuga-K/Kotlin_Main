package questJournal2

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.modules.audio.synth.SampleNode
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf

// Flow корутины
import kotlinx.coroutines.launch                    // запуск корутин
import kotlinx.coroutines.flow.MutableSharedFlow    // радиостанция событий
import kotlinx.coroutines.flow.SharedFlow           // чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow     // табло состояний
import kotlinx.coroutines.flow.StateFlow            // только для чтения
import kotlinx.coroutines.flow.asSharedFlow         // отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow          // отдать только StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest


// questJournal 2.0 - список активных квестов, цели, маркеры, подсказки
// QuestSystem - будет обрабатывать информацию о нынешных квестов активного игрока
// и UI выводить актуальную информацию

// МАРКЕРЫ и ТИПЫ КВЕСТОВ

enum class QuestStatus{
    ACTIVE,
    COMPLETED,
    FAILED
}

enum class QuestMarker{
    NEW,
    PINNED,
    COMPLETED,
    NONE
}

// Подготовка журнала квестов - то что будет отрисовывать UI

data class QuestJournalEntry(
    val questId: String,
    val title: String,  // Отображение название квеста
    val status: QuestStatus,
    val objectiveQuest: String, // Подсказка "что делать дальше"
    val marker: QuestMarker,
    val markerHint: String
)

// ------------------- СОБЫТИЯ, что будет влиять на UI и другие системы

sealed interface GameEvent{
    val playerId: String
}

data class QuestJournalUpdated(
    override val playerId: String
): GameEvent

// Игрок открыл квест - поменять маркер NEW
data class QuestOpened(
    override val playerId: String,
    val questId: String
): GameEvent

data class QuestPinned(
    override val playerId: String,
    val questId: String
): GameEvent

data class QuestProggressed(
    override val playerId: String,
    val questId: String
): GameEvent

// ------------- Команды UI -> Сервер

sealed interface GameCommand{
    val playerId: String
}

// Игрок открыл квест - поменять маркер NEW
data class CmdOpenQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdPinQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdProggressQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdSwitchPlayer(
    override val playerId: String,
    val questId: String
): GameCommand

// ------------ Серверные данные квеста

data class QuestStateOnServer(
    val questId: String,
    val title: String,
    val step: Int,
    val status: QuestStatus,
    val isNew: Boolean,
    val isPinned: Boolean
)

class QuestSystem{
    // Здесь прописываем текст целей квестов по шагам на каждого квеста

    private fun objectiveFor(questId: String, step: Int): String{
        return when (questId){
            "q_alchemist" -> when (step) {
                0 -> "Поговорить с алхимиком"
                1 -> "Собери траву"
                2 -> "Принеси траву"
                else -> "Квест завершён"
            }
            "q_guard" -> when (step) {
                0 -> "поговорить стражем этой двери"
                1 -> "Заплатить 10 золота"
                else -> "Проход открыт"
            }

            else -> "Неизвестный квест"
        }
    }

    // Подсказки куда идти - в будущем используем для карты в компассе
    private fun markerHintFor(questId: String, step: Int): String{
        return when (questId){
            "q_alchemist" -> when (step) {
                0 -> "Иди к НПС (алхимик)"
                1 -> "Соберать Herb x2"
                2 -> "Вернись к НПС (алхимик)"
                else -> "Квест завершён"
            }
            "q_guard" -> when (step) {
                0 -> "Иди к НПС (СТРАЖ)"
                1 -> "Заплати 10 золота"
                else -> "Готовот"
            }

            else -> ""
        }
    }

    // Превращаем QuestStateOnServer в том что отобразиться на UI
    fun toJournalEntry(quest: QuestStateOnServer): QuestJournalEntry{
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

// ------------ Сервер - обработка квестов, принятие команд и рассылка событий

class GameServer{
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _commands = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _commands.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean{
        // TryEmit - попытка быстро положить команду в поток
        return _commands.tryEmit(cmd)
    }


    // состояние квестов для каждого игрока

    private val _questByPlayer = MutableStateFlow<Map<String, List<QuestStateOnServer>>>(
        mapOf(
            "Oleg" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, true),
                QuestStateOnServer("q_guard", "Тебе сюда нельзя", 0, QuestStatus.ACTIVE, true, false)
            ),
            "Stas" to listOf(
                QuestStateOnServer("q_alchemist", "Алхимик и трава", 0, QuestStatus.ACTIVE, true, true),
                QuestStateOnServer("q_guard", "Тебе сюда нельзя", 0, QuestStatus.ACTIVE, true, false)
            )
        )
    )
    val questByPlayer: StateFlow<Map<String, List<QuestStateOnServer>>> = _questByPlayer.asStateFlow()
}