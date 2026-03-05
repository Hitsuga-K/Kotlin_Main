package lesson7

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

//в игре, которая зависит от общего игрового прогресса игроков - клиент не должен уметь менять квесты, золото, инвентарь
// Клиент иначе можно будет взломать, только сервер будет решать, что можно, а что нельзя, и сервер синхронизирует всё между игроками одинаково

// Аннотации - разделение кусков кода на серверные и клиентские (мы сами говорим программе, что где должно работать)
// Правильная цепочка безопасного кода:
// 1. Клиент (через hud или кнопку) отправляет команду на сервер:
// "Я поговорил с алхимиком"
// 2. Сервер принимает команду, проверяет правила, которые мы ему установили (соблюдено ли условие 5 золота)
// 3. Сервер рассылает события (GameEvent) с информацией (Reward / Refuse)
// 4. Клиент получает информацию о том, можно ли пройти дальше

enum class QuestState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    THREAD_ACCEPTED,
    GOOD_END,
    EVIL_END
}

data class DialogueOption(
    val id: String,
    val text: String,
)
data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)
class Npc(
    val id: String,
    val name: String
){
    fun dialogueFor(state: QuestState): DialogueView{
        return when(state){
            QuestState.START -> DialogueView(
                name,
                "Привет! Нажми Talk, чтобы начать диалог",
                listOf(
                    DialogueOption("talk", "Говорить")
                )
            )
            QuestState.OFFERED -> DialogueView(
                name,
                "Поможешь?",
                listOf(
                    DialogueOption("help", "Помочь"),
                    DialogueOption("thread", "Драться")
                )
            )
            QuestState.HELP_ACCEPTED -> DialogueView(
                name,
                "ПОБЕДА!",
                emptyList()
            )
            QuestState.THREAD_ACCEPTED -> DialogueView(
                name,
                "Не хочу драться",
                emptyList()
            )
            QuestState.GOOD_END -> DialogueView(
                name,
                "Спасибо за помощь!",
                emptyList()
            )
            QuestState.EVIL_END -> DialogueView(
                name,
                "Забирай всё",
                emptyList()
            )
        }
    }
}

//GameState (показывает только HUD)

class ClientUiState{
    //Состояние внутри  него, будут обновляться от серверных данных

    val playerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val questState = mutableStateOf(QuestState.START)
    val networkLagMs = mutableStateOf(350)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(ui: ClientUiState, text: String){
    ui.log.value = (ui.log.value + text).takeLast(20)
}

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

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: QuestState
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

typealias Listener = (GameEvent) -> Unit

class EventBus{
    private val listeners = mutableStateListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for (l in listeners){
            l(event)
        }
    }
}

// Команда - "запрос клиента на сервер"
sealed interface GameCommand {
    val playerId: String
}

data class CmdTalkToNpc(
    override val playerId: String,
    val npcId: String
): GameCommand

data class CmdResetQuest(
    override val playerId: String,
    val questId: String
): GameCommand

data class CmdSelectedChoice(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameCommand

data class CmdLoadPlayer(
    override val playerId: String
): GameCommand

// SERVER WORLD - серверные данные и обработка команд

// PlayerData
data class PlayerData(
    var hp: Int,
    var gold: Int,
    var questState: QuestState
)

//Команда, которая ждёт выполнения (симуляция пинга)
data class PendingCommand(
    val cmd: GameCommand,
    var delayLeftSec: Float
)

class  ServerWorld(
    private val bus: EventBus
){
    //словарь всех игроков сервера
    private val questId = "q_alchemist"
    private val serverPlayers = mutableMapOf<String, PlayerData>()

    //Очередь выполнения команд - inbox. с учётом пинга
    private val inbox = mutableListOf<PendingCommand>()

    // Метод проверки, существования игрока в базе данных, и если его нет - создаём
    private fun ensurePlayer(playerId: String): PlayerData{
        val existing = serverPlayers[playerId]
        if (existing != null) return existing
        //Если пользователь существует в базе данных, то вернуть его, если нет - идём дальше и создаём его

        val created = PlayerData(
            100,
            0,
            QuestState.START
        )
        serverPlayers[playerId] = created
        return created
    }
    // Снимок серверных данных
    fun getSnapShot(playerId: String): PlayerData{
        val player = ensurePlayer(playerId)

        // копия важна, т.к. мы в клиенте не можем менять информацию об игроке
        // мы отправляем (return) новый объект PlayerData, чтобы клиент не мог изменить, но мог прочесть и отобразить
        return PlayerData(
            player.hp,
            player.gold,
            player.questState
        )
    }
    fun sendCommand(cmd: GameCommand, networkLagMs: Int){
        val lagSec =  networkLagMs / 1000f
        // перевод миллисекунд в секунды

        // добавляем в очередь выполнения команд
        inbox.add(
            PendingCommand(
                cmd,
                lagSec
            )
        )
    }
    // Метод  update вызывается каждый кадр, нужен для уменьшения задержки и выполнения команд, которые дошли
    fun update(deltaSec: Float){
        //delta - сколько прошло времени с прошлого кадра (Time.deltaT)

        //уменьшаем таймер у каждой команды, за прошедшее delta время
        for (pending in inbox){
            pending.delayLeftSec -= deltaSec
        }

        // отфильтруем очередь с отдельным списком, с командами, готовыми к выполнению
        val ready = inbox.filter { it.delayLeftSec <= 0f }
        // удалям команды, которые надо выполнить из списка очереди
        inbox.removeAll(ready)

        for (pending in ready){
            applyCommand(pending.cmd)
        }
    }
    private fun applyCommand(cmd: GameCommand){
        val player = ensurePlayer(cmd.playerId)

        when(cmd){
            is CmdTalkToNpc ->{
                //публикация события от сервера всей игре - это подтверждение сервера, что игрок поговорил
                bus.publish(TalkedToNpc(cmd.playerId, cmd.npcId))

                val newState = nextQuestState(player.questState, TalkedToNpc(cmd.playerId, cmd.npcId), cmd.npcId)
                setQuestState(cmd.playerId, player, newState)
            }
            is CmdResetQuest -> {
                setQuestState(cmd.playerId, player, QuestState.START)
                bus.publish(PlayerProgressSaved(
                    cmd.playerId,
                    "Квест сброшен вручную"
                ))
            }
            is CmdSelectedChoice -> {
                bus.publish(ChoiceSelected(cmd.playerId, cmd.npcId, cmd.choiceId))

                val newState = nextQuestState(player.questState, ChoiceSelected(cmd.playerId, cmd.npcId, cmd.choiceId), cmd.npcId)
                setQuestState(cmd.playerId, player, newState)
            }
            is CmdLoadPlayer -> {
                loadPlayerFormDisk(cmd.playerId, player)
                // После загрузки сохранения игрока - желательно тоже сохранить событием
                bus.publish(PlayerProgressSaved(cmd.playerId, "Игрок загрузил сохранения с диска"))
            }

        }
    }
    // Правила квеста ( state machine)
    private fun nextQuestState(current: QuestState, event: GameEvent, npcId: String): QuestState{
        //npcId - нужен, чтобы не реагировать на других NPC, не связанных с этапом квеста

        if (npcId != "alchemist") return current

        return when (current){
            QuestState.START -> when (event){
                is TalkedToNpc -> QuestState.OFFERED
                else -> QuestState.START
                // Если состояние квеста START и проходит событие TalkedToNpc тогда поменять состояние квеста на OFFERED
            }

            QuestState.OFFERED -> when(event){
                is ChoiceSelected -> {
                    if (event.choiceId == "help") QuestState.HELP_ACCEPTED else QuestState.THREAD_ACCEPTED
                }
                else -> QuestState.OFFERED
            }

            QuestState.THREAD_ACCEPTED -> when(event){
                is ChoiceSelected -> {
                    if(event.choiceId == "threat_confirm") QuestState.EVIL_END else QuestState.THREAD_ACCEPTED
                }
                else -> QuestState.THREAD_ACCEPTED
            }

            QuestState.HELP_ACCEPTED -> QuestState.GOOD_END
            QuestState.GOOD_END -> QuestState.GOOD_END
            QuestState.EVIL_END -> QuestState.EVIL_END
        }

    }
    private fun setQuestState(playerId: String, player: PlayerData, newState: QuestState){
        val old = player.questState
        if (newState == old) return

        player.questState = newState

        bus.publish(
            QuestStateChanged(
                playerId,
                questId,
                newState
            )
        )

        bus.publish(
            PlayerProgressSaved(
                playerId,
                "Игрок перешёл на новый этам квеста ${newState.name}")
        )
    }
    //Сохранение и загрузка на сервере

    private  fun saveFile(playerId: String): File{
        val dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${playerId}_server.save")
    }

    fun savePlayerToDisk(playerId: String){
        val player = ensurePlayer(playerId)
        val file = saveFile(playerId)

        val sb = StringBuilder()
        // пустой сборщик строк

        sb.append("playerId=").append(playerId).append("\n")
        // append - добавление текста в конец списка
        sb.append("hp=").append(player.hp).append("\n")
        sb.append("gold=").append(player.gold).append("\n")
        sb.append("questState=").append(player.questState.name).append("\n")
        //name - превратит enum  в строку например: "START"

        val text = sb.toString()
        // toString - получить финальную строку из StringBuilder

        file.writeText(text)
    }
    private fun loadPlayerFormDisk(playerId: String, player: PlayerData){
        val file = saveFile(playerId)
        if (!file.exists()) return

        val map = mutableMapOf<String, String>()
        // словарь который будет в себе хранить 2 части строки с учётом разделителя
        // hp=100 -  в ключ занесём hp в значение 100

        for (line in file.readLines()){
            val parts = line.split("=")
            //Поделить цельную строку на 2 части с учётом разделителя =
            if (parts.size == 2){
                map[parts[0]] = parts[1]
            }
        }
        player.hp = map["hp"]?.toIntOrNull() ?: 100
        player.gold = map["gold"]?.toIntOrNull() ?: 0

        val stateName = map["questState"] ?: QuestState.START.name

        player.questState = try{
            QuestState.valueOf(stateName)
        } catch (e: Exception){
            QuestState.START
        }
    }
}

//SaveSystem - отдельная система, которая слушает события и вызывает save на сервере
class  SaveSystem(
    private val bus: EventBus,
    private val server: ServerWorld
){
    init {
        bus.subscribe { event ->
            if (event is PlayerProgressSaved){
                server.savePlayerToDisk(event.playerId)
            }
        }
    }
}
class Client(
    private val ui: ClientUiState,
    private val server: ServerWorld
){
    fun send(cmd: GameCommand){
        // UI -> Server отправка команды с текущим пингом
        server.sendCommand(cmd, ui.networkLagMs.value)
    }

    fun syncFromServer(){
        //Берём снимок данных с сервера
        val snap = server.getSnapShot(ui.playerId.value)

        // После получения копии данных - обновляем клиентский UI state
        ui.hp.value = snap.hp
        ui.gold.value = snap.gold
        ui.questState.value = snap.questState
    }
}
fun main() = KoolApplication {
    val ui = ClientUiState()
    val bus = EventBus()
    val server = ServerWorld(bus)
    val saveSystem = SaveSystem(bus, server)
    val client = Client(ui, server)

    val npc = Npc("alchemist", "Алхимик")

    bus.subscribe { event ->
        val line = when(event){
            is TalkedToNpc -> "EVENT: Игрок ${event.playerId} поговорил с ${event.npcId}"
            is ChoiceSelected -> "EVENT: Игрок ${event.playerId} выбрал вариант ответа  ${event.choiceId}"
            is QuestStateChanged -> "EVENT: Квест ${event.questId} перешёл на этап ${event.newState}"
            is PlayerProgressSaved -> "EVENT: Сохранено для ${event.playerId} причина - ${event.reason}"
        }
        pushLog(ui, "[${event.playerId}] $line")
    }

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube{colored()} }

            shader = KslPbrShader{
                color {vertexColor()}
                metallic (0.7f)
                roughness (0.4f)
            }
            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }
        onUpdate{
            server.update(Time.deltaT)
            client.syncFromServer()
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)
        addPanelSurface {

         modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(14.dp)
            Column {
                val qState = ui.questState.use()
                Text("Player: ${ui.playerId.use()}") {}
                Text("HP: ${ui.hp.use()}") {}
                Text("Gold: ${ui.gold.use()}") {}
                Text("Quest Step: ${ui.questState.use()}") {}
                Text("Пинг: ${ui.networkLagMs.use()} мс") {}

                Row {
                    Button("Повысить пинг до 50") {
                        modifier
                            .margin(8.dp)
                            .onClick {
                                ui.networkLagMs.value = 50
                                pushLog(ui, "Пинг увеличен до ${ui.networkLagMs.value} мс")
                            }
                    }
                    Button("Повысить пинг до 350 ") {
                        modifier
                            .margin(10.dp)
                            .onClick {
                                ui.networkLagMs.value = 350
                                pushLog(ui, "Пинг увеличен до ${ui.networkLagMs.value} мс")
                            }
                    }
                    Button("Повысить пинг до 1200 ") {
                        modifier
                            .margin(12.dp)
                            .onClick {
                                ui.networkLagMs.value = 1200
                                pushLog(ui, "Пинг увеличен до ${ui.networkLagMs.value} мс")
                            }
                    }
                }

                Row {
                    Button("Переключить игроков") {
                        modifier.margin(end = 8.dp).onClick {
                            ui.playerId.value =
                                if (ui.playerId.value == "Player") "Player_1" else "Player_2"
                        }
                    }
                    // Метод сохранения не прописан, поэтому нечего будет загружать. (Не работает)
                    Button("Сохранить игру") {
                        modifier.margin(end = 8.dp).onClick {
                            pushLog(ui, "Запрос сохранения отправлен на сервер...")
                        }
                    }

                    Button("Загрузить игру") {
                        modifier.margin(end = 8.dp).onClick {
                            client.send(CmdLoadPlayer(ui.playerId.value))
                            pushLog(ui, "Запрос загрузки отправлен на сервер...")
                        }
                    }
                    Button("Начать квест заново"){
                        modifier.margin(end = 8.dp).onClick{
                            client.send(CmdResetQuest(ui.playerId.value, "q_alchemist"))
                            pushLog(ui, "Запрос на сброс квеста отправлен на сервер...")

                        }
                    }
                }
                val dialog = npc.dialogueFor(qState)

                Text("${dialog.npcName}:") {
                    modifier.margin(top = sizes.gap)
                }
                Text(dialog.text) {
                    modifier.margin(bottom = sizes.smallGap)
                }

                Row {
                    // перебор всех вариантов ответов диалога
                    for (opt in dialog.options) {
                        Button(opt.text) {
                            modifier.margin(end = 8.dp).onClick {
                                val pid = ui.playerId.value

                                when (opt.id) {
                                    "talk" -> client.send(CmdTalkToNpc(pid, "alchemist"))
                                    "help" -> client.send(CmdSelectedChoice(pid, "alchemist", "help"))
                                    "thread" -> client.send(CmdSelectedChoice(pid, "alchemist", "thread"))
                                    "threat_confirm" -> client.send(CmdSelectedChoice(pid, "alchemist", "thread_confirm"))
                                }
                            }
                        }
                    }
                }
                // Логироване
                Text("LOG:") { modifier.margin(top = sizes.gap) }
                for (line in ui.log.use()) {
                    Text(line) { modifier.font(sizes.smallText) }
                }
            }
        }
    }
}





