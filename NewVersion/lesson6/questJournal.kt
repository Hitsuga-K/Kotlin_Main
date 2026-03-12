package lesson6

import de.fabmax.kool.KoolApplication   // Запускает Kool-приложение
import de.fabmax.kool.addScene          // функция - добавить сцену (UI, игровой мир и тд)

import de.fabmax.kool.math.Vec3f        // 3D - вектор (x,y,z)
import de.fabmax.kool.math.deg          // deg - превращение числа в градусы
import de.fabmax.kool.scene.*           // Сцена, камера, источники света и тд

import de.fabmax.kool.modules.ksl.KslPbrShader  // готовый PBR Shader - материал
import de.fabmax.kool.util.Color        // Цветовая палитра
import de.fabmax.kool.util.Time         // Время deltaT - сколько прошло секунд между двумя кадрами

import de.fabmax.kool.pipeline.ClearColorLoad // Режим говорящий не очищать экран от элементов (нужен для UI)

import de.fabmax.kool.modules.ui2.*     // импорт всех компонентов интерфейса, вроде text, button, Row....
import jdk.jfr.Event
import lesson3.WOOD_SWORD
import lesson4.DamageDealt
import lesson4.ItemAdded
import lesson5.QuestState

import java.io.File
import java.security.Guard

// startWith('quest:') - проверка с чего начинается строка
// substringAfter('quest') - добавить "кусок" строки после префикса
// try {что пытаемся сделать} catch (e: Exception) {сделать то что произойдёт в случае "падения" при загрузке try}
// try catch - не "положит" весь код fun main если произойдёт ошибка

class GameState{
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String) {
    game.log.value = (game.log.value + text).takeLast(20)
}

// sealed - иерархия классов
// это вид классов который только хранит в себе другие классы
// interface - тип класса которыц обязует все дочерние классы - перезаписать свойства которые мы положим в вторичный конструктор
sealed interface GameEvent{
    val playerId: String
}

data class TalkedToNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent

data class ItemCollected(
    override val playerId: String,
    val itemId: String,
    val count: Int
): GameEvent

data class ItemGivenToNpc(
    override val playerId: String,
    val itemId: String,
    val count: Int,
    val npcId: String
): GameEvent

data class GoldPaidToNpc(
    override val playerId: String,
    val npcId: String,
    val count: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newStateName: String
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

// -=-=-=-=-=-=-

typealias Listener = (GameState) -> Unit

class EventBus{
    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener) {
        listeners.add(listener)
    }

    fun publish(event: GameEvent) {
        for (listener in listeners) {
            listener(event)
        }
    }
}

// -=-=-=-=-=-=-=-=-=-=-=-

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

// QuestDefinition - "описание квеста" будет интерфейсом т.е. набором правил для всех квестов при их создании
// любой новый квест при создании будет наследовать из данного интерфейса все свойства, методы

interface QuestDefinition {
    val questId: String

    fun initialStateName(): String
    // состояние которое будет принимать квест в момень создания

    fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String
    // метод который проверяет нынешнее состояние и возвращает следующее к котормому он перейдёт при event срьытии

    fun stateDescription(stateName: String): String
    // Описание этапа квеста для квестового журнала

    fun npcDialogue(stateName: String): DialogueView
    // метод указывает что скажет npc и какие кнопки покажет в диалоге
}

// -=-=-=-=-=-= Создание квеста с алхимиком (Экземпляр интерфейса QuestDefinition) =-=-=-=-=-=-

enum class AlchemistState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    HERB_COLLECTED,
    TREAT_ACCEPTED,
    GOOD_END,
    EVIL_END
}
class AlchemistQuest: QuestDefinition {
    override val questId: String = "q_alchemist"

    override fun initialStateName(): String {
        return AlchemistState.START.name
    }

    private fun safeState(stateName: String) : AlchemistState {
        // valueOf - может "положить" наш код если строка окажется неправильной
        return try {
            AlchemistState.valueOf(stateName)
        } catch (e: Exception){
            AlchemistState.START
        }
    }

    override fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String {
        val current = safeState(currentStateName)

        val next: AlchemistState = when(current){
            AlchemistState.START -> when(event){
                is TalkedToNpc -> {
                    if(event.npcId == "Alchemist") AlchemistState.OFFERED else AlchemistState.START
                }
                else -> AlchemistState.START
            }

            AlchemistState.OFFERED -> when(event){
                is ChoiceSelected -> {
                    if (event.npcId != "Alchemist") AlchemistState.OFFERED
                    else if (event.choiceId == "help") AlchemistState.HELP_ACCEPTED
                    else if(event.choiceId == "threat") AlchemistState.TREAT_ACCEPTED
                    else AlchemistState.OFFERED
                }
                else -> AlchemistState.OFFERED
            }

            AlchemistState.HELP_ACCEPTED -> when(event){
                is ItemCollected -> {
                    if (event.itemId == "herb") AlchemistState.HERB_COLLECTED else AlchemistState.HELP_ACCEPTED
                }
                else -> AlchemistState.OFFERED
            }

            AlchemistState.HERB_COLLECTED -> when(event){
                is ItemGivenToNpc -> {
                    if(event.npcId == "Alchemist" && event.itemId == "herb") AlchemistState.GOOD_END
                    else AlchemistState.HERB_COLLECTED
                }
                else -> AlchemistState.HERB_COLLECTED
            }

            AlchemistState.TREAT_ACCEPTED -> when(event){
                is ChoiceSelected -> {
                    if(event.npcId == "Alchemist" && event.choiceId == "threat_confirm") AlchemistState.EVIL_END
                    else AlchemistState.TREAT_ACCEPTED
                }
                else -> AlchemistState.TREAT_ACCEPTED
            }

            AlchemistState.GOOD_END -> AlchemistState.GOOD_END
            AlchemistState.EVIL_END -> AlchemistState.EVIL_END
        }
        return next.name
    }

    override fun stateDescription(stateName: String): String{
        return when(safeState(stateName)){
            AlchemistState.START -> "Поговорить с Алхимиком"
            AlchemistState.OFFERED -> "Помочь или угрожать"
            AlchemistState.HELP_ACCEPTED -> "Собрать 1 траву"
            AlchemistState.HERB_COLLECTED -> "Отдать траву"
            AlchemistState.TREAT_ACCEPTED -> "Подтвердить угрозу"
            AlchemistState.GOOD_END -> "HAPPY END"
            AlchemistState.EVIL_END -> "YouGonnaDieInThisHouse"
        }
    }

    override fun npcDialogue(stateName: String): DialogueView {
        return when(safeState(stateName)){
            AlchemistState.START -> DialogueView(
                "Алхимик",
                "Привет!!!!!! come here",
                listOf(DialogueOption("talk", "Поговорить"))
            )
            AlchemistState.OFFERED -> DialogueView(
                "Алхимик",
                "bring to me herb pls",
                listOf(
                    DialogueOption("help", "Help (bring herb"),
                    DialogueOption("threat", "No give me gold!")
                )
            )

            AlchemistState.HELP_ACCEPTED -> DialogueView(
                "Алхимик",
                "Bring to me 1 herb, deal?",
                listOf(
                    DialogueOption("collect_herb", "collect herb"),
                    DialogueOption("talk", "Talk more")
                )
            )

            AlchemistState.HERB_COLLECTED -> DialogueView(
                "Алхимик",
                "Great, give me that",
                listOf(
                    DialogueOption("give_herb", "*give herb*")
                )
            )
            AlchemistState.TREAT_ACCEPTED -> DialogueView(
                "Алхимик",
                "kid are you sure?!",
                listOf(
                    DialogueOption("threat_confirm", "Yes! Give me all your money!!!")
                )
            )
            AlchemistState.GOOD_END -> DialogueView(
                "Алхимик",
                "ty, take that useless healing potion and 50 gold (happy end)",
                emptyList()
            )
            AlchemistState.EVIL_END -> DialogueView(
                "Алхимик",
                "Fine *give gold*",
                emptyList()
            )

        }
    }
}

// -=-=-=-=-=-= Квест со стражником (вещи или бан) =-=-=-=-=-=-
enum class GuardState{
    START,
    OFFERED,
    WAIT_PAYMENT,
    PASSED,
    BANNED
}

class GuardQuest: QuestDefinition{
    override val questId: String = "q_guard"

    override fun initialStateName(): String = GuardState.START.name

    private fun safeState(stateName: String) : GuardState {
        // valueOf - может "положить" наш код если строка окажется неправильной
        return try {
            GuardState.valueOf(stateName)
        } catch (e: Exception){
            GuardState.START
        }
    }

    override fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String {
        val current = safeState(currentStateName)

        val next: GuardState = when(current){
            GuardState.START -> when(event){
                is TalkedToNpc -> {
                    if (event.npcId == "guard") GuardState.OFFERED else GuardState.START
                }
                else -> GuardState.START
            }

            GuardState.OFFERED -> when (event){
                is ChoiceSelected -> {
                    if (event.npcId != "quard") GuardState.OFFERED
                    else if (event.choiceId == "pay") GuardState.WAIT_PAYMENT
                    else if (event.choiceId == "refuse") GuardState.BANNED
                    else GuardState.OFFERED
                }
                else -> GuardState.OFFERED
            }

            GuardState.WAIT_PAYMENT -> when(event){
                is GoldPaidToNpc -> {
                    if (event.npcId == "guard" && event.count >= 5) GuardState.PASSED
                    else GuardState.WAIT_PAYMENT
                }
                else -> GuardState.WAIT_PAYMENT
            }

            GuardState.BANNED -> GuardState.BANNED
            GuardState.PASSED -> GuardState.PASSED
        }
        return next.name
    }

    override fun stateDescription(stateName: String): String{
        return when(safeState(stateName)){
            GuardState.START -> "talk with Guardikom"
            GuardState.OFFERED -> "pay 5 gold or you will baned"
            GuardState.WAIT_PAYMENT -> "..Guard still waiting.."
            GuardState.PASSED -> "You passed"
            GuardState.BANNED -> "Nah you BANED"
        }
    }

    override fun npcDialogue(stateName: String): DialogueView {
        return when(safeState(stateName)){
            GuardState.START -> DialogueView(
                "Guardik",
                "Halloy, cmere!",
                listOf(DialogueOption("talk", "Поговорить"))
            )
            GuardState.OFFERED -> DialogueView(
                "Guardik",
                "You need pay for enter into kingdom",
                listOf(
                    DialogueOption("pay", "Fine fine take it *give gold*"),
                    DialogueOption("refuse", "no I don't have enough gold")
                )
            )
            GuardState.WAIT_PAYMENT -> DialogueView(
                "Guardik",
                "Good boy~ you passed",
                listOf(DialogueOption("done", "now I'm poor, bye -_-"))
            )

            GuardState.PASSED -> DialogueView(
                "Guardik",
                "Do you want something?",
                listOf(DialogueOption("no", "no, I'm just walk"))
            )
            GuardState.BANNED -> DialogueView(
                "Guardik",
                "Get out! you're not welcome here",
                listOf(DialogueOption("walk_away", "*walk away*"))
            )
        }
    }

    // перезапишите функцию для описания состояний (stateDesctiprion)
    // для каждого состояния - написать подсказку к чему нужно прийти на данном этапе квеста

    // перезаписать npcDialogue - обязательно вернуть String
}

class QuestManager(
    private val bus: EventBus,
    private val game: GameState,
    private val quests: List<QuestDefinition>
){
    val stateByPlayer = mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
    // внешний ключ - playerId
    // Внутренний ключ - questId
    // Внутреннее значение - состояние квеста на момент сохранения

    init {
        bus.subscribe { event ->
            handleEvent(event)
        }
    }

    fun getStateName(playerId: String, questId: String): String {
        val playerMap = stateByPlayer.value[playerId]

        if (playerMap == null) {
            val def = quests.firstOrNull{it.questId == questId == questId}
            return def?.initialStateName() ?: "UNKNOWN"
        }

        return playerMap[questId] ?: (quests.firstOrNull{it.questId == playerId}?.initialStateName() ?: "UNKNOWN")
    }

    fun setStateName(playerId: String, questId: String, stateName: String): String {
        val outerCopy = stateByPlayer.value.toMutableMap()

        val innerOld = outerCopy.remove(playerId) ?: emptyMap()

        val innerCopy = innerOld.toMutableMap()
        innerCopy[playerId] = stateName

        outerCopy[playerId] = innerCopy
        stateByPlayer.value = outerCopy.toMap()
    }

//    private fun handleEvent(event: lesson4.GameEvent){
//        // Решаем влияет ли событие на квест (реагирует ли на событие)
//        val player = event.playerId
//        val step = getStep(player)
//
//        // Если квест уже выполен уже выполнен
//        if(step >= 2) return
//
//        when(event){
//            is ItemAdded -> {
//                // Шаг квеста: 0
//                if (step == 0 && event.itemId == WOOD_SWORD.id){
//                    completeStep(player, 0)
//                }
//            }
//
//            is DamageDealt -> {
//                // шаг квеста 1 ударить манекен мечом
//                if (step == 1 && event.targetId == "dummy" && event.amount >=10){
//                    completeStep(player, 1)
//                }
//            }
//            else -> {}
//        }
//    }

    private fun handleEvent(event: GameEvent) {
        val player = event.playerId

        for (quest in quests) {
            val questId = quest.questId
            val currentState = getStateName(player, questId)
            val nextState = quest.nextStateName(currentState, event, game)

            // continue это похоже на штуку которую мы изучали типо если не выполнилось - пропустить и идти дальше без ошибок
            if (nextState == currentState) continue

            setStateName(player, questId, nextState)

            bus.publish(
                QuestStateChanged(
                    playerId = player,
                    questId = questId,
                    newStateName = nextState
                )
            )

            bus.publish(
                PlayerProgressSaved(
                    playerId = player,
                    reason = "updated"
                )
            )
        }
    }

    // private fun handleEvent(принимает событие)
    // получить playerId
    // сделать for для перебора всех квестов внутри quests
        // Получить состояние квеста ненышнее
        // и то к которому он прейдёт

        // Проверить что следующее состояние НЕ равняется нынешнему (тупик)
            // вызываете метод setStateName(... ... ...)

            // Публикуете событие, что состояние квеста изменено

            // публикуете событие что прогресс игрока сохранён
}