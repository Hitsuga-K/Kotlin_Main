package lesson6

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.FLT_EPSILON

import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.

import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами

import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"

import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf
import de.fabmax.kool.util.DynamicStruct

import java.io.File

//startWith('quest:') - проверка, с чего начинается строка
// substringAfter('quest:') - добавить "кусок" строки после префикса
// try {что пытаемся сделать } catch (e: Exception) {сделать то, что произойдёт в случае "падения" при загрузке try}
// try catch - не "положит" весь код fun main, если произойдёт ошибка

class GameState{
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String){
    game.log.value = (game.log.value + text).takeLast(20)
}

//sealed - иерархия классов
// это вид классов, который только хранит в себе другие классы
// interface - тип класса, который обязует все дочерние классы - перезаписать свойства, которые мы положим в вторичный конструктор
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

//----------------------------------------------------------
//typealias  - это псевдоним в которую можно положить тип данных
//чтобы заменять строки (GameEvent) -> Unit переменной Listener
typealias Listener = (GameEvent) -> Unit

class EventBus{
    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }
    fun publish(event: GameEvent){
        for (listener in listeners){
            listener(event)
        }
    }
}

// -------------------

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

// QuestDefinition - "описание квеста" будет интерфейсом, то есть набором правил для всех квестов, при их создании
// Любой новый квест, при создании будет наследовать из данного интерфейса все свойства, методы

interface QuestDefinition{
    val questId: String

    fun initialState(): String
    // Состояние, которое будет принимать квест, в момент создания

    fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String
    // метод, который проверяет нынешнее состояние и возвращает следующее, к которому он перейдёт при event событии

    fun stateDescription(stateName: String) : String
    // Описание квеста, для квестового журнала

    fun npcDialogue(stateName: String): DialogueView
    // Метод указывает, что скажет npc и какие кнопки покажет в диалоге
}

// --------------------Создание квеста с алхимиком(Экземпляр интерфейса QuestDefinition)---------------------

enum class AlchemistsState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    HERB_COLLECTED,
    THREAT_ACCEPTED,
    GOOD_END,
    EVIL_END
}
class  AlchemistQuest: QuestDefinition{
    override val questId: String = "q_alchemist"

    override fun initialState(): String {
        return AlchemistsState.START.name
    }
    private fun safeState(stateName: String) : AlchemistsState{
        // valueOf - может "положить" код, если строка окажется неправильной
        return try{
            AlchemistsState.valueOf(stateName)
        } catch (e: Exception){
            AlchemistsState.START
        }
    }

    override fun nextStateName(currentStateName: String, event: GameEvent, game: GameState): String {
        val current = safeState(currentStateName)

        val next: AlchemistsState = when(current){
            AlchemistsState.START -> when(event){
                is TalkedToNpc -> {
                    if(event.npcId == "Alchemist") AlchemistsState.OFFERED else AlchemistsState.START
                }
                else -> AlchemistsState.START
            }

            AlchemistsState.OFFERED -> when(event){
                is ChoiceSelected -> {
                    if(event.npcId != "Alchemist") AlchemistsState.OFFERED
                    else if (event.choiceId == "help") AlchemistsState.HELP_ACCEPTED
                    else if (event.choiceId == "threat") AlchemistsState.THREAT_ACCEPTED
                    else AlchemistsState.OFFERED
                }
                else -> AlchemistsState.OFFERED
            }

            AlchemistsState.HELP_ACCEPTED -> when(event){
                is ItemCollected -> {
                    if (event.itemId == "herb") AlchemistsState.HERB_COLLECTED else AlchemistsState.HELP_ACCEPTED
                }
                else -> AlchemistsState.OFFERED
            }

            AlchemistsState.HERB_COLLECTED -> when(event){
                is ItemGivenToNpc -> {
                    if(event.npcId == "Alchemist" && event.itemId == "herb") AlchemistsState.GOOD_END
                    else AlchemistsState.HERB_COLLECTED
                }
                else -> AlchemistsState.HERB_COLLECTED
            }

            AlchemistsState.THREAT_ACCEPTED -> when(event) {
                is ChoiceSelected -> {
                    if (event.npcId == "Alchemist" && event.choiceId == "treat_confirm") AlchemistsState.EVIL_END
                    else AlchemistsState.THREAT_ACCEPTED
                }

                else -> AlchemistsState.THREAT_ACCEPTED
            }
                AlchemistsState.GOOD_END -> AlchemistsState.GOOD_END
                AlchemistsState.EVIL_END -> AlchemistsState.EVIL_END
            }
            return next.name
        }

    override fun stateDescription(stateName: String): String {
        return when(safeState(stateName)){
            AlchemistsState.START -> "Поговорить с алхимиком"
            AlchemistsState.OFFERED -> "Помочь или угрожать"
            AlchemistsState.HELP_ACCEPTED -> "Собрать 1 траву"
            AlchemistsState.HERB_COLLECTED -> "Отдать траву алхимику"
            AlchemistsState.THREAT_ACCEPTED -> "Подтвердить угрозу"
            AlchemistsState.GOOD_END -> "Квест завершён (хорошая концовка)"
            AlchemistsState.EVIL_END -> "Квест завершён (плохая концовка)"
        }
    }

    override fun npcDialogue(stateName: String): DialogueView {
        return when(safeState(stateName)){
            AlchemistsState.START -> DialogueView(
                "Алхимик",
                "Привет! Подойти перестём за траву",
                listOf(DialogueOption("talk", "Поговорить"))
            )

            AlchemistsState.OFFERED -> DialogueView(
                "Алхимик",
                "Мне нужна трава, подсобишь?",
                listOf(
                    DialogueOption("help", "Помочь (принести травы)"),
                    DialogueOption("threat", "Угрожать (требовать золото)")
                )
            )

            AlchemistsState.HERB_COLLECTED -> DialogueView(
                "Алхимик",
                "Принеси 1 траву и мы в расчёте",
                listOf(
                    DialogueOption("collect_herb", "Собрать траву"),
                    DialogueOption("talk", "Поговорить ещё")
                )
            )
            AlchemistsState.HELP_ACCEPTED -> DialogueView(
                "Алхимик",
                "Отлично, давай траву",
                listOf(
                    DialogueOption("give_herb", "Отдать 1 траву")
                )
            )
            AlchemistsState.THREAT_ACCEPTED -> DialogueView(
                "Алхимик",
                "Ты уверен?",
                listOf(
                    DialogueOption("threat_confirm", "Да, гони золото")
                )
            )

            AlchemistsState.GOOD_END -> DialogueView(
                "Алхимик",
                "Спасибо, держи зелье здоровья и 50 золота (GOOD END)",
                    emptyList()
                )

            AlchemistsState.EVIL_END -> DialogueView(
                "Алхимик",
                "Ладно, держи своё золото, но ты об этом пожалеешь (EVIL END)",
                emptyList()
            )
        }
    }
}

// --------------- Квест со стражником (вещь или бан)
enum class GuardState {
    START,
    OFFERED,
    WAIT_PAYMENT,
    PASSED,
    BANNED
}
class GuardQuest: QuestDefinition{
    override val questId: String = "q_guard"

    override fun initialState(): String = GuardState.START.name

    private fun safeState(stateName: String): GuardState{
        return try{
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
                    if (event.npcId != "guard") GuardState.OFFERED
                    else if(event.choiceId == "pay") GuardState.WAIT_PAYMENT
                    else if (event.choiceId == "refuse") GuardState.BANNED
                    else GuardState.OFFERED
                }
                else -> GuardState.OFFERED
            }

            GuardState.WAIT_PAYMENT -> when(event){
                is GoldPaidToNpc -> {
                    if (event.npcId == "guard" && event.count >= 5 ) GuardState.PASSED
                    else GuardState.WAIT_PAYMENT
                }
                else -> GuardState.WAIT_PAYMENT
            }

            GuardState.PASSED -> GuardState.PASSED
            GuardState.BANNED -> GuardState.BANNED
        }
        return  next.name
    }
    override fun stateDescription(stateName: String): String {
        return when (safeState(stateName)) {
            GuardState.START -> "Поговорить с охранником"
            GuardState.OFFERED -> "Купить охранника"
            GuardState.WAIT_PAYMENT -> "Ожидание покупки"
            GuardState.PASSED -> "Вы купили охранника"
            GuardState.BANNED -> "Вы были заблокированы"
        }
    }
    override fun npcDialogue(stateName: String): DialogueView {
        return when(safeState(stateName)){
            GuardState.START -> DialogueView(
                "Охранник",
                "Чего надо?",
                listOf(DialogueOption("talk", "Поговорить"))
            )

            GuardState.OFFERED -> DialogueView(
                "Охранник",
                "Что ты хочешь?",
                listOf(DialogueOption("buy", "Хочу выкупить тебя"))
            )

            GuardState.WAIT_PAYMENT -> DialogueView(
                "Охранник",
                "Жди. Сейчас, пересчитаю..",
                listOf(DialogueOption("wait", "Ожидание.."),
                DialogueOption("refuse", "Забрать деньги"))
            )

            GuardState.PASSED -> DialogueView(
                "Охранник",
                "Хорошо, проходи.",
                listOf(DialogueOption("passed", "Идите дальше"))
            )

            GuardState.BANNED -> DialogueView(
                "Охранник",
                "Пожизненный бан!",
                listOf(DialogueOption("banned", "Вы были забанены"))
            )
        }
    }
}
    //перезапишите функцию для описания состояния
    // для каждого состояния - написать подсказку к чему нужно прийти на данном этапе квеста

    // перезаписать npcDialogue - обязательно вернуть String


class QuestManager(
    private val bus: EventBus,
    private val game: GameState,
    private val quests: List<QuestDefinition>
){
    val stateByPlayer = mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
    // внешний ключ - playerId
    // Внутренний ключ - questId
    //Внутреннее значение - состояние квеста на момент сохранения
    init {
        bus.subscribe { event ->

        }
    }
    private fun handleEvent(event: GameEvent){
        val pid = event.playerId

        for (quest in quests){
            val current = getStateName(pid, quest.questId)
            val next = quest.nextStateName(current, event, game)
            if (next != current){
                setStateName(pid, quest.questId, next)

                bus.publish(
                    QuestStateChanged(
                        playerId = pid,
                        questId = quest.questId,
                        next
                    )
                )
                bus.publish(
                    PlayerProgressSaved(
                        playerId = pid,
                        reason = "Quest ${quest.questId} изменён в состояние $next"
                    )
                )
            }
        }
    }
    fun getStateName(playerId: String, questId: String): String{
        val playerMap = stateByPlayer.value[playerId]

        if (playerMap == null){
            val def = quests.firstOrNull{it.questId == questId}
            return def?.initialState() ?: "UNKNOWN"
        }
        return playerMap[questId] ?: (quests.firstOrNull{it.questId == questId}?.initialState() ?: "UNKNOWN")
}
    fun setStateName(playerId: String, questId: String, stateName: String){
    val outerCopy = stateByPlayer.value.toMutableMap()

    val innerOld =  outerCopy[playerId] ?: emptyMap()

    val innerCopy = innerOld.toMutableMap()
        innerCopy[playerId] = stateName


    outerCopy[playerId] = innerCopy.toMap()
        stateByPlayer.value = outerCopy.toMap()
    }
}
// ------------- Cохранение квестов в 1 файл -------------------
class  SaveSystem (
    private  val bus: EventBus,
    private val game: GameState,
    private val questManager: QuestManager,
    private val quest: List<QuestDefinition>
){
    init {
        bus.subscribe { event ->
            if (event is PlayerProgressSaved){
                saveAllForPlayer(event.playerId)
            }
        }
    }
    private fun saveFile(playerId: String) :File{
        val dir = File("saves")
        if(!dir.exists()) dir.mkdirs()
        return File(dir, "${playerId}.save")
    }
    private fun saveAllForPlayer(playerId: String){
        val f = saveFile(playerId)
        val sb = StringBuilder()

        sb.append("playerId=").append(playerId).append("\n")
        sb.append("hp=").append(game.hp.value).append("\n")
        sb.append("gold=").append(game.gold.value).append("\n")

        for (q in quest){
            val stateName = questManager.getStateName(playerId, q.questId)
            sb.append("quest:").append(q.questId).append("=").append(stateName).append("\n")
        }
        f.writeText(sb.toString())
        //"playerId=$playerId\n" +
        //"questId=$questId\n" +
        // //"state=$stateName\n" +
        //каждый + создаёт новую строку (объект)
        // и если строк много (а их будет много, из-за больших сохранений, файлов конфигурации файлов логов, файлов настройки)
        // то генерируются лишнее временные строки, появляется лишняя нагрузка на память, тяжёлая читаемость когда строк очень много

        // что в данной истории делает StringBuilder (ок как коробка, в которую постепенно дописыватеся текст
        // append - добавляет не новую строку( как в списке)
        // в конце "билда" делаем toSring - преобразует в итоговую ОДНУ финальную строку
    }
    fun loadAllForPlayer(playerId: String){
        val f = saveFile(playerId)
        if (!f.exists()) return
        //прервать если файла сохранения нет

        val map = mutableMapOf<String, String>()

        for (line in f.readLines()){
            val part = line.split("=")
            if (part.size == 2){
                map[part[0]] = part[1]
            }
        }
        val loadedHp = map["hp"]?.toIntOrNull() ?: 100
        val loadedGold = map["gold"]?.toIntOrNull() ?: 0

        game.hp.value = loadedHp
        game.gold.value = loadedGold

        //загрузка квестов
        for ((key, value) in map){
            if (key.startsWith("quest:")){
                // startWith - проверка на то, с чего начинается кусок строки

                val questId = key.substringAfter("quest:")
                    //substringAfter - берёт часть строки, после quest:
                // пример ключ "quest:q_guard" -> substringAfter вернёт только q_guard

                questManager.setStateName(playerId, questId, value)
                // подгружаем этап квеста, на котором остановился игрок во время сохранения
            }
        }
    }
}
fun main()= KoolApplication{
    val game = GameState()
    val bus = EventBus()

    val alchemistQuest = AlchemistQuest()
    val guardQuest = GuardQuest()
    val questList = listOf<QuestDefinition>(alchemistQuest, guardQuest)
    //создаём список квестов и кладём туда наши квесты

    val questManager = QuestManager(bus, game, questList)
    val saves = SaveSystem(bus, game, questManager, questList)

    val activeNpcId = mutableStateOf<String?>(null)
    // если у npc null значит игрок ещё не открыл диалог с  ним
}