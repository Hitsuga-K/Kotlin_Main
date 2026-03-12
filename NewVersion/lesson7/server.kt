package lesson7

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

import java.io.File
import java.security.Guard

// В игре которая зависит от общего игрового процесса игроков - клиент не должен уметь менять квесты, золото, инвентарь
// Клиент иначе можно будет взломаьт, только сервер будет решать что можно а что нельзя и сервер синхронизирует всё между игроками

// Аннотации - разделение кусков кода на серверные и клиентские (мы сами говорим программе что где должно работать)
// Правильная цепочка безопасного кода
// 1. Клиент (через hub или кнопку) отпавляет команду на сервер:
// "Я поговорил с алхимиком"
// 2. Сервер принимает команду проверяет правила которые ему установили (соблюдено ли условие 5 золота)
// 3. Сервер рассылает событие (GameEvent) с информацией (Reward / Refuse)
// 4. Клиент получает информацию о том можно ли пройти дальше

enum class QuestState{
    START,
    OFFERED,
    HELP_ACCEPTED,
    THREAT_ACCEPTED,
    GOOD_END,
    EVIL_END
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcName: String,
    val text: String,
    val options: List<DialogueOption>
)

class Npc(
    val npcId: String,
    val name: String
){
    fun dialogueFor(state: QuestState): DialogueView{
        return when(state){
            QuestState.START -> DialogueView(
                name,
                "Привет! нажми talk чтобы начать диалог",
                listOf(
                    DialogueOption("talk", "Говорить")
                )
            )
            QuestState.OFFERED -> DialogueView(
                name,
                "помоги или казнь",
                listOf(
                    DialogueOption("help", "помочь"),
                    DialogueOption("threat", "казнь так казнь")
                )
            )
            QuestState.HELP_ACCEPTED -> DialogueView(
                name,
                "ура спс",
                emptyList()
            )
            QuestState.THREAT_ACCEPTED -> DialogueView(
                name,
                "ну всё смэрть",
                emptyList()
            )
            QuestState.GOOD_END -> DialogueView(
                name,
                "хэппи энд",
                emptyList()
            )
            QuestState.EVIL_END -> DialogueView(
                name,
                "бэд энд",
                emptyList()
            )
        }

    }
}

// GameState (показывает только HUB)

class ClientUiState{
    // состояния внутри него будут обновляться от серверных данных

    val playerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val questState = mutableStateOf(QuestState.START)
    val networkLagMs = mutableStateOf(350)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(ui: ClientUiState, text: String) {
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
    private val listeners = mutableListOf<Listener>()
    // Список всех, кто реагирует на события (слушателей)
    // private - позволяет читать, вызывать и использовать список только внутри класса (сейчас только внутри GameEvent)
    fun subscribe(listener: Listener){
        listeners.add(listener)
        // .add - добавляет в конец списка
    }

    fun publish(event: GameEvent){
        // Метод рассылки событий для слушателей
        for (l in listeners){
            l(event)
        }
    }
}

// Команды - "запрос клиента на сервер"

sealed interface GameCommand{
    val playerId: String
}

data class CmdTalkToNpc(
    override val playerId: String,
    val npcId: String,
): GameCommand

data class CmdSelectChoice(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameCommand

data class CmdLoadPlayer(
    override val playerId: String,
): GameCommand

data class CmdSavePlayer(
    override val playerId: String,
    val hp: Int,
    val gold: Int
) : GameCommand

data class CmdResetQuest(
    override val playerId: String
) : GameCommand


// SERVER WORLD - серверные данные и обработка команд

//PlayerData
data class PlayerData(
    var hp: Int,
    var gold: Int,
    var questState: QuestState
)

//комманда которая ждёт выполнения (симуляция пинга)
data class PendingCommand(
    val cmd: GameCommand,
    var delayLeftSec: Float
)

class ServerWorld(
    private val bus: EventBus
){
    private val questId = "q_alchemist"

    // Словарь всех игроков сервера
    private val serverPlayers = mutableMapOf<String, PlayerData>()

    // inbox - очередь выполнения команд с учётом пинга
    private val inbox = mutableListOf<PendingCommand>()

    // Метод проверки существования игрока в базе данных и еслии его нет - создаем
    private fun ensurePlayer(playerId: String): PlayerData {
        val existing = serverPlayers[playerId]
        if (existing != null) return existing
        // если пользователь существует в базе данных то вернуть его, если нет - идём дальше и создаём его

        val created = PlayerData(
            100,
            0,
            QuestState.START
        )
        serverPlayers[playerId] = created
        return created
    }
    // Снимок серверных данных
    fun getSnapshot(playerId: String): PlayerData{
        val player = ensurePlayer(playerId)

        // Копия важна - так как мы в клиенте не можем менять информацмю об игроке
        // мы отправляем (return) новый объект PlayerData чтобы клиент не мог изменить но мог прочесть и отобразить
        return PlayerData(
            player.hp,
            player.gold,
            player.questState
        )
    }

    // Метод для отправки комманды на сервер от коиента
    fun sendCommand(cmd: GameCommand, networkLagMs: Int){
        val lagSec = networkLagMs / 1000f
        // перевод милисекунд в секунды

        // добавляем в очередь выполнения команд
        inbox.add(
            PendingCommand(
                cmd,
                lagSec
            )
        )
    }

    // метод update вызывается каждый кадр, нужен для уменьшения задержки и выполнения команд которые дошлт
    fun update(deltaSec: Float){
        // delta - сколько прошло времени с прошлого кадра(Time.deltaT)
        // уменьшаем таймер у каждой команды за прошедшее delta время
        for (pending in inbox){
            pending.delayLeftSec -= deltaSec
        }

        // отфильтруем очередь с отдельным списком с командами готовыми к выполнению
        val ready = inbox.filter{ it.delayLeftSec <= 0f}

        //удаляем команды которые надо выполнить из списка очереди
        inbox.removeAll(ready)

        for (pending in ready){
            applyCommand(pending.cmd)
        }
    }

    private fun applyCommand(cmd: GameCommand){
        val player = ensurePlayer(cmd.playerId)

        when (cmd){
            is CmdTalkToNpc -> {
                // публикация события от сервера всей игре - это подтверждение сервера что игрок поговорил
                bus.publish(TalkedToNpc(cmd.playerId, cmd.npcId))

                // после рассылки сервер меняет соответсвтвено правилам которые прописанным в dialogueFor
                val newState = nextQuestState(player.questState, TalkedToNpc(cmd.playerId, cmd.npcId), cmd.npcId)
                setQuestState(cmd.playerId, player, newState)
            }
            is CmdSelectChoice -> {
                bus.publish(ChoiceSelected(cmd.playerId, cmd.npcId, cmd.choiceId))

                // после рассылки сервер меняет соответсвтвено правилам которые прописанным в dialogueFor
                val newState = nextQuestState(player.questState, ChoiceSelected(cmd.playerId, cmd.npcId, cmd.choiceId), cmd.npcId)
                setQuestState(cmd.playerId, player, newState)
            }

            is CmdLoadPlayer -> {
                loadPlayerFromDisk(cmd.playerId, player)
                // после загрузки сохранения игрока - желательно тоже сохранить событем
                bus.publish(PlayerProgressSaved(cmd.playerId, "Игрок загрузил сохранения с диска"))
            }
            is CmdSavePlayer -> {
                savePlayerToDisk(cmd.playerId)
                bus.publish(PlayerProgressSaved(cmd.playerId, "Сохранено"))
            }
            is CmdResetQuest -> {
                player.questState = QuestState.START
                bus.publish(QuestStateChanged(cmd.playerId, questId, QuestState.START)
                bus.publish(PlayerProgressSaved(cmd.playerId, "квест сброшен"))
            }

        }
    }

    // правила квеста (state machine)
    private fun nextQuestState(current: QuestState, event: GameEvent, npcId: String): QuestState {
        // npcId -нужен чтобы не реагировать на других нпс не связанных с этапом квеста

        if (npcId != "alchemist") return current

        return when (current) {
            QuestState.START -> when (event) {
                is TalkedToNpc -> QuestState.OFFERED
                else -> QuestState.START
                //если состояние квеста START и проходит событие TalkedToNpc тогда поменять состояние квеста на OFFERED
            }
            QuestState.OFFERED -> when(event){
                is ChoiceSelected -> {
                    if(event.choiceId == "help") QuestState.HELP_ACCEPTED else QuestState.THREAT_ACCEPTED
                }
                else -> QuestState.OFFERED
            }

            QuestState.THREAT_ACCEPTED -> when(event){
                is ChoiceSelected -> {
                    if (event.choiceId == "threat_confirm") QuestState.EVIL_END else QuestState.THREAT_ACCEPTED
                }
                else -> QuestState.THREAT_ACCEPTED
            }

            QuestState.HELP_ACCEPTED -> QuestState.GOOD_END
            QuestState.GOOD_END -> QuestState.GOOD_END
            QuestState.EVIL_END -> QuestState.EVIL_END
        }
    }
    private fun setQuestState(playerId: String, player: PlayerData, newState: QuestState) {
        val old = player.questState
        if (newState != old) return

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
                "Игрок перешёл на новый этап квеста ${newState.name}"
            )
        )
    }
    // Сохранение и загрузка на сервер
    private fun saveFile(playerId: String): File{
        val dir = File("saves")
        if(!dir.exists()) dir.mkdirs()
        return File(dir, "${playerId}_server.save")
    }

    fun savePlayerToDisk(playerId: String){
        val player = ensurePlayer(playerId)
        val file = saveFile(playerId)

        val sb = StringBuilder()
        // Пустой сборщик строк

        sb.append("playerId=").append(playerId).append("\n")
        // append - добавление текста в конец списка
        sb.append("hp=").append(player.hp).append("\n")
        sb.append("gold=").append(player.gold).append("\n")
        sb.append("questState=").append(player.questState.name).append("\n")
        // name - превратит enum в строку например "START"

        val text = sb.toString()
        // toString - получить финальную строку из StringBuilder

        file.writeText(text)
    }

    private fun loadPlayerFromDisk(playerId: String, player: PlayerData){
        val file = saveFile(playerId)
        if (!file.exists()) return

        val map = mutableMapOf<String, String>()
        // словарь который будет в себе брать 2 части строки с учётом разделителя
        // hp=100 - ключ занесем в hp в значение 100

        for (line in file.readLines()){
            val parts = line.split("=")
            //поделить цельную строку на 2 части с учётом разделителя =
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

// SaveSystem - отдельная система которая слушает события и вызывает save на сервере
class SaveSystem(
    private val bus: EventBus,
    private val server: ServerWorld
){
    init {
        bus.subscribe { event ->
            if (event is PlayerProgressSaved) {
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
        // ui -> Server отправка команды с текущим пингом
        server.sendCommand(cmd, ui.networkLagMs.value)
    }

    fun syncFromServer(){
        // берём снимок данных с сервера
        val snap = server.getSnapshot(ui.playerId.value)

        // После получения копии данных обновляем клиентский UI state
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
            is TalkedToNpc -> "[EVENT] Игрок ${event.playerId} поговорил с ${event.npcId}"
            is ChoiceSelected -> "[EVENT] Игрок ${event.playerId} выбрад вариант ответа: ${event.choiceId}"
            is QuestStateChanged -> "[EVENT] Квест ${event.questId} перешёл на этап ${event.newState}"
            is PlayerProgressSaved -> "[EVENT] Сохранено для ${event.playerId} причина - ${event.reason}"
        }
        pushLog(ui, "[${ui.playerId}] $line")
    }

    addScene {
        defaultOrbitCamera()
        addColorMesh {
            generate { cube{colored()} }

            shader = KslPbrShader {
                color { vertexColor()}
                metallic(0.7f)
                roughness(0.4f)
            }

            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.X_AXIS)
            }

            lighting.singleDirectionalLight {
                setup(Vec3f(-1f, -1f, -1f))
                setColor(Color.WHITE, 5f)
            }

            onUpdate{ // главный цикл сервера
                server.update(Time.deltaT) // сервер обрабатывает очередь команд
                client.syncFromServer() // клиент обновляет HUB из серверных данныхн
            }
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            //modifier
            // выровнять по верхнему углу
            // отступить снаружи 16 dp
            // сделать фон 0f 0f 0f 0.6 и скруглить вместе с фоном углы на 14.dp
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .background(RoundRectBackground(Color(0f,0f,0f,0.6f), 14.dp))
                .margin(16.dp)

            Column{
                // Выводите информациб о статах что за игрок, какой хп, сколько золота
                // важно не просто получать значение value а читать изменения состояний

                Text("Игрок: ${ui.playerId.use()}"){}
                Text("HP: ${ui.hp.use()}"){}
                Text("Gold: ${ui.gold.use()}"){}
                Text("QuestState: ${ui.questState.use()}"){}

                val qState = ui.questState.use()

                // Отображаете нынешний пинг
                Text("Ping: ${ui.networkLagMs}"){}

                Row{
                    // Создаете 3 кнопки с помощью которых вы можете менять пинг
                    // 50ms 350ms 1200ms
                    // так же важно сделать между кнопками отступ чтобы они сливались
                    Button("ping: 50ms") {
                        modifier
                            .padding(end = 10.dp)
                            .onClick{
                                ui.networkLagMs.value = 50
                            }
                    }
                    Button("ping: 350ms") {
                        modifier
                            .padding(end = 10.dp)
                            .onClick{
                                ui.networkLagMs.value = 350
                            }
                    }
                    Button("ping: 1200ms") {
                        modifier
                            .padding(end = 10.dp)
                            .onClick{
                                ui.networkLagMs.value = 1200
                            }
                    }
                }
                Row{
                    // Отступ
                    // Сделать кнопку переключения игроков
                    Button("смена игрока") {
                        modifier
                            .padding(end = 12.dp)
                            .onClick{
                                ui.playerId.value = if (ui.playerId.value == "Oleg") "TrippiTroppa" else "Oleg"
                            }
                    }
                    // Отступ
                    modifier.margin(12.dp)
                    // Кнопка загрузки сохранения игрока
                    // ВАЖНО клиент не должен загружать файл напрямую
                    // Клиент должен просить у сервера загрузить игрока
                    Button("загрузить сохранение") {
                        modifier
                            .padding(end = 12.dp)
                            .onClick {
                                client.send(CmdLoadPlayer(ui.playerId.value))
                            }
                    }
                    Button("Сохранить") {
                        modifier
                            .padding(end = 12.dp)
                            .onClick {
                                client.send(CmdSavePlayer(ui.playerId.value, ui.hp.value, ui.gold.value))
                            }
                    }
                    Button("Сброс") {
                        modifier
                            .padding(end = 12.dp)
                            .onClick {
                                client.send(CmdResetQuest(ui.playerId.value))
                            }
                    }
                }
                val dialog = npc.dialogueFor(qState)

                Text("${dialog.npcName}"){
                    modifier.margin(top = sizes.gap)
                }
                Text(dialog.text){
                    modifier.margin(top = sizes.smallGap)
                }

                Row {
                    // Перебор всех вариантов ответа
                    for(opt in dialog.options){
                        Button (opt.text) {
                            modifier.margin(end = 8.dp)
                                .onClick{
                                    val pid = ui.playerId.value

                                    // Клиент отправляет не события а команды серверу что он нажал
                                    when(opt.id){
                                        "talk" -> client.send(CmdTalkToNpc(pid, "alchemist"))
                                        "help" -> client.send(CmdSelectChoice(pid, "alchemist", "help"))
                                        "threat" -> client.send(CmdSelectChoice(pid, "alchemist", "threat"))
                                        "threat_confirm" -> client.send(CmdSelectChoice(pid, "alchemist", "threat_confirm"))

                                    }
                                }
                        }
                    }
                }
                // Логирование
                Text("[LOG]"){ modifier.margin(top = sizes.gap)}
                for (line in ui.log.use()){
                    Text(line) {modifier.font(sizes.smallText)}
                }
            }
        }
    }
}
