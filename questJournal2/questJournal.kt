 package questJournal2

import
 de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
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

    fun start(scope: kotlinx.corountines.CoroutineScope){
        scope.launch{
            commands.collect{ cmd ->
                process(cmd)
            }
        }
    }

    private suspend fun process(cmd: GameCommand){
        when (cmd){
            is CmdOpenQuest -> openQuest(cmd.playerId, cmd.questId)
            is CmdProgressQuest -> progressQuest(cmd.playerId, cmd.questId)
            is CmdPinQuest -> pinQuest(cmd.playerId, cmd.questId)
            is CmdSwitchPlayer -> {}
        }
    }

    private fun getPlayerQuests(playerId: String) : List<QuestStateOnServer>{
        return _questByPlayer.value[playerId] ?: emptyList()
    }
    private fun setPlayerQuests(playerId: String, quests: List<QuestStateOnServer>){
        val oldMap = _questByPlayer.value.toMutableMap()
        oldMap[playerId] = quests
        _questByPlayer.value = oldMap.toMap()
    }

    private suspend fun openQuest(playerId: String, questId: String){
        val quests = getPlayerQuests(playerId).toMutableList()

        for(i in quests.indices){
            val q = quests[i]
            if (q.questId == questId){
                quests[i] = q.copy(isNew = false)
            }
        }

        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }

    private suspend fun pinQuest(playerId: String, questId: String)
        val quests = getPlayerQuests(playerId).toMutableList()

        for(i in quests.indices){
            val q = quests[i]
            if (q.questId == questId){
                quests[i] = q.copy(isPinned = (q.questId == questId))
            }
        }

        setPlayerQuests(playerId, quests)

        _events.emit(QuestJournalUpdated(playerId))
    }

 private suspend fun progressQuest(playerId: String, questId: String){
     val quests = getPlayerQuests(playerId).toMutableList()

     for (i in quests.indices){
         val q = quests[i]
         if (q.questId == questId){
             val newStep = q.step + 1

             val completed = when(q.questId){
                 "q_alchemist" -> newStep >= 3
                 "q_guard" -> newStep >= 2
                 else -> false
             }

             val newStatus = if (completed) QuestStatus.COMPLETED else QuestStatus.ACTIVE

             quests[i] = q.copy(isNew = false, step = newStep, status = newStatus)
         }
         setPlayerQuests(playerId, quests)

         _events.emit(QuestJournalUpdated(playerId))
     }
}
class HudState{
    val activePlayerIdFlow = MutableStateOf("5445")
    val activePlayerIdUi = mutableStateOf("5445")

    val questEntries = mutableStateOf<List<QuestJournalEntry>>(emptyList())
    val selectedQuestId = mutableStateOf<String?>(null)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hub: HudState, text: String){
     hud.log.value = (hud.log.value + text). takeLast(20)
}

fun markerSymbol(m:QuestMarker): String{
    return when(m){
        QuestMarker.NEW -> "!"
        QuestMarker.PINNED -> "->"
        QuestMarker.COMPLETED -> "🎈"
        QuestMarker.NONE -> "🎄"
    }
}
fun main() = KoolApplication {
    val hud = HudState()
    val server = GameSrever()
    val quests =

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

         val server = GameServer()

         val saver = SaveSystem()
         val damage = DamageSystem(server)
         val cooldowns = CooldownSystem(server, coroutineScope)
         val poison = PoisonSystem(server, coroutineScope)
         val quests = QuestSystem(server, coroutineScope)
         val attackSpeedBuff = AttackSpeedBuffSystem(server, coroutineScope) // Создали новую систему

         Shared.server = server
         Shared.saver = saver
         Shared.damage = damage
         Shared.cooldowns = cooldowns
         Shared.poison = poison
         Shared.quests = quests
         Shared.attackSpeedBuff = attackSpeedBuff

         coroutineScope.launch {
             server.events.collect { event ->
                 damage.onEvent(event)
             }
         }

         coroutineScope.launch {
             server.events.collect { event ->
                 poison.onEvent(event) { dmg ->
                     if (!server.tryPublish(dmg)) {
                         coroutineScope.launch { server.publish(dmg) }
                     }
                 }
             }
         }

         coroutineScope.launch {
             server.events.collect { event ->
                 quests.onEvent(event) { newEvent ->
                     if (!server.tryPublish(newEvent)) {
                         coroutineScope.launch { server.publish(newEvent) }
                     }
                 }
             }
         }

         coroutineScope.launch {
             server.events.collect { event ->
                 attackSpeedBuff.onEvent(event)
             }
         }
         coroutineScope.launch {
             server.events.collect { event ->
                 if (event is SaveRequested) {
                     val snapShot = server.getPlayer(event.playerId)
                     saver.save(snapShot)
                 }
             }
         }
     }

     addScene {
         setupUiScene(ClearColorLoad)

         val server = Shared.server

         if (server != null) {
             coroutineScope.launch {
                 server.events.collect { event ->
                     val line = when (event) {
                         is AttackPressed -> "${event.playerId} атаковал ${event.targetId}"
                         is DamageDealt -> "${event.targetId} получил ${event.amount} урона"
                         is PoisonApplied -> "на ${event.playerId} наложен яд на ${event.ticks} тиков"
                         is TalkedToNpc -> "${event.playerId} начал разговор с ${event.npcId}"
                         is ChoiceSelected -> "${event.playerId} выбрал ${event.choiceId}"
                         is SaveRequested -> "Запрос на сохранение"
                         is QuestStateChanged -> "${event.playerId} перешёл на новый этап квеста ${event.newState}"
                         is AttackSpeedBuffApplied -> "${event.playerId} получил бафф скорости на ${event.ticks} сек"
                         is CommandRejected -> " Отказ: ${event.reason}" // Отображаем отказ
                         else -> "Неизвестное событие"
                     }
                     lesson10.hudLog(hud, "$line")
                 }
             }

             coroutineScope.launch {
                 server.players.collect { playerMap ->
                     val pid = hud.activePlayerId.value
                     val player = playerMap[pid] ?: return@collect

                     hud.hp.value = player.hp
                     hud.gold.value = player.gold
                     hud.poisonTicksLeft.value = player.poisonTicksLeft
                     hud.questState.value = player.questState
                     hud.attackCooldownMsLeft.value = player.attackCooldownMsLeft
                     hud.attackSpeedBuffTicks.value = player.attackSpeedBuffTicks // Обновляем отображение баффа
                 }
             }
         }

         addPanelSurface {
             modifier

             Text("Player: ${hud.activePlayerId.use()}") {}
             Text("HP: ${hud.hp.use()} | Gold: ${hud.gold.use()}") {
                 modifier.margin(bottom = sizes.gap)
             }

             Text("Quest: ${hud.questState.use()}") {}
             Text("Poison ticks: ${hud.poisonTicksLeft.use()}") {}
             Text("Attack speed buff: ${hud.attackSpeedBuffTicks.use()}") { // Отображаем бафф
                 modifier.margin(bottom = sizes.gap)
             }
             Text("Cooldown left: ${hud.attackCooldownMsLeft.use()} ms") {
                 modifier.margin(bottom = sizes.gap)
             }

             Column {
                 Button("Поменять игрока (Oleg/Stas)") {
                     modifier.margin(bottom = 8.dp)
                         .onClick {
                             hud.activePlayerId.value =
                                 if (hud.activePlayerId.value == "Oleg") "Stas" else "Oleg"
                         }
                 }

                 Button("Атаковать Артемия (даму полусвета)") {
                     modifier.margin(bottom = 8.dp)
                         .onClick {
                             val server = Shared.server ?: return@onClick
                             val playerId = hud.activePlayerId.value
                             val targetId = "Artemi"

                             val cooldowns = Shared.cooldowns
                             if (cooldowns != null && !cooldowns.canAttack(playerId)) {
                                 val rejectEvent = CommandRejected(playerId, "Атака еще на перезарядке! Осталось ${hud.attackCooldownMsLeft.use()} мс")
                                 if (!server.tryPublish(rejectEvent)) {
                                     coroutineScope.launch { server.publish(rejectEvent) }
                                 }
                                 return@onClick
                             }

                             val attackEvent = AttackPressed(playerId, targetId)
                             val published = if (!server.tryPublish(attackEvent)) {
                                 coroutineScope.launch { server.publish(attackEvent) }
                                 true
                             } else {
                                 true
                             }

                             if (published) {
                                 val damageEvent = DamageDealt(playerId, targetId, 15)
                                 if (!server.tryPublish(damageEvent)) {
                                     coroutineScope.launch { server.publish(damageEvent) }
                                 }
                                 cooldowns?.startCooldown(playerId, 1200)
                             }
                         }
                 }

                 Button("Бафф скорости атаки на 5 сек") {
                     modifier.margin(bottom = 8.dp)
                         .onClick {
                             val server = Shared.server ?: return@onClick
                             val playerId = hud.activePlayerId.value

                             val buffEvent = AttackSpeedBuffApplied(playerId, 5) // 5 тиков баффа
                             if (!server.tryPublish(buffEvent)) {
                                 coroutineScope.launch { server.publish(buffEvent) }
                             }
                             lesson10.hudLog(hud, "Ты выпил энергетик и у тебя остановилось сердце $playerId")
                         }
                 }

                 Row {
                     modifier.margin(bottom = 8.dp)
                     Button("Поговорить с алхимиком") {
                         modifier.margin(end = 8.dp)
                             .onClick {
                                 val server = Shared.server ?: return@onClick
                                 val playerId = hud.activePlayerId.value
                                 val talkEvent = TalkedToNpc(playerId, "alchemist")
                                 if (!server.tryPublish(talkEvent)) {
                                     coroutineScope.launch { server.publish(talkEvent) }
                                 }
                             }
                     }
                     Button("Выбрать помочь") {
                         modifier.margin(end = 8.dp)
                             .onClick {
                                 val server = Shared.server ?: return@onClick
                                 val playerId = hud.activePlayerId.value
                                 val choiceEvent = ChoiceSelected(playerId, "alchemist", "help")
                                 if (!server.tryPublish(choiceEvent)) {
                                     coroutineScope.launch { server.publish(choiceEvent) }
                                 }
                             }
                     }
                 }

                 Button("Отравить себя (колой не черной)") {
                     modifier.margin(bottom = 8.dp)
                         .onClick {
                             val server = Shared.server ?: return@onClick
                             val playerId = hud.activePlayerId.value
                             val poisonEvent = PoisonApplied(playerId, 3, 5, 2000)
                             if (!server.tryPublish(poisonEvent)) {
                                 coroutineScope.launch { server.publish(poisonEvent) }
                             }
                         }
                 }

                 Button("Save JSON") {
                     modifier.margin(bottom = 8.dp)
                         .onClick {
                             val server = Shared.server ?: return@onClick
                             val playerId = hud.activePlayerId.value
                             val event = SaveRequested(playerId)
                             if (!server.tryPublish(event)) {
                                 this@addScene.coroutineScope.launch {
                                     server.publish(event)
                                 }
                             }
                         }
                 }

                 Text("Логи:") {
                     modifier.margin(top = 8.dp, bottom = 4.dp)
                 }
                 Column {
                     hud.log.use().forEach { logLine ->
                         Text("• $logLine") {
                         }
                     }
                 }
             }
         }
     }
 }
