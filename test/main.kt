package realGaneScene

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.deg                  // deg - превращает число в "градусы" (угол)
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.flatMapLatest

enum class QuestState {
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

// -= Типы объектов игрового мира
enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE,
    CHEST ////////////////////
}

// -= Описание объектов в игрровом мире
data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

data class NpcMemory(
    val hasMet: Boolean, // -= встретил или нет
    val timesTalked: Int, // -= Сколько раз уже поговорил
    val receivedHerb: Boolean,
    val sawPlayerNearSource: Boolean = false,
    val isStopped: Boolean = false,
    val posX: Float = 3f,
    val posZ: Float = 3f
)

data class PlayerState(
    val playerId: String,
    val posX: Float,
    val posZ: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>, // -= примитивный словарь
    val alchemistMemory: NpcMemory,
    val currentAreaId: String?,
    val hintText: String, // -= Подсказка что делать и тп
    val gold: Int
)

// -=-=-= Вспомогательные функции =-=-=-
fun herbCount(player: PlayerState): Int{ //> даёт количество herb
    return player.inventory["herb"] ?: 0
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // sqrt((dx * dx) + (dz * dz))
    val dx = ax - bx
    val dz = az - bz
    return kotlin.math.sqrt(dx*dx + dz*dz)
}

fun initialPlayerState(playerId: String): PlayerState {
    return if(playerId == "Stas"){
        PlayerState(
            "Stas",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                true,
                2,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
        )
    }else{
        PlayerState(
            "Oleg",
            0f,
            0f,
            QuestState.START,
            emptyMap(),
            NpcMemory(
                false,
                0,
                false
            ),
            null,
            "Подойди к одной из локаций",
            0
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val npcId: String,
    val text: String,
    val options: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView{
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START -> {
            val greeting =
                if (!memory.hasMet) {
                    "Привет, ты кто"
                }else{
                    "Ну что ${player.playerId} я жду?!"
                }
            DialogueView(
                "Алхимик",
                "$greeting\nТащи траву",
                listOf(
                    DialogueOption("accept_help", "Акей"),
                    DialogueOption("threat", "Нит, ты давай")
                )
            )
        }
        QuestState.WAIT_HERB -> {
            if (herbs < 3) {
                DialogueView(
                    "Алхимик",
                    "Мало, мне надо 4 вщто",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "спс",
                    listOf(
                        DialogueOption("give_herb", "Отдать 4 травы")
                    )
                )
            }
        }
        QuestState.GOOD_END -> {
            val text =
                if (memory.receivedHerb){
                    "Ну что, похимичим?!"
                }else{
                    "Ты завершил квест, но память не обновилась, капут"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }
        QuestState.EVIL_END -> {
            DialogueView(
                "Алхимик",
                "я с тобой больше не дружу",
                emptyList()
            )
        }
    }
}

sealed interface GameCommand{
    val playerId: String
}
data class CmdMovePlayer(
    override val playerId: String,
    val dx: Float,
    val dz: Float
): GameCommand

data class CmdInteract(
    override val playerId: String
): GameCommand

data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayer: String
): GameCommand

data class CmdResetPlayer(
    override val playerId: String
): GameCommand

sealed interface GameEvent{
    val playerId: String
}

data class EnteredArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class LeftArea(
    override val playerId: String,
    val areaId: String
): GameEvent

data class InteractedWithNpc(
    override val playerId: String,
    val npcId: String
): GameEvent

data class InteractedWithHerbSource(
    override val playerId: String,
    val sourceId: String
): GameEvent

data class InventoryChanged(
    override val playerId: String,
    val itemId: String,
    val newCount: Int
): GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val newState: QuestState
): GameEvent

data class NpcMemoryChanged(
    override val playerId: String,
    val memory: NpcMemory
): GameEvent

data class ServerMessage(
    override val playerId: String,
    val text: String
): GameEvent

class GameServer{
    val worldObjects = listOf(
        WorldObjectDef(
            "alchemist",
            WorldObjectType.ALCHEMIST,
            -3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "herb_source",
            WorldObjectType.HERB_SOURCE,
            3f,
            0f,
            1.7f
        ),
        WorldObjectDef(
            "chest",
            WorldObjectType.CHEST,
            1f,
            0f,
            1.7f
        )
    )

    private  val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _command = MutableSharedFlow<GameCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<GameCommand> = _command.asSharedFlow()

    fun trySend(cmd: GameCommand): Boolean = _command.tryEmit(cmd)

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to initialPlayerState("Oleg"),
            "Stas" to initialPlayerState("Stas")
        )
    )
    val players: StateFlow<Map<String, PlayerState>> = _players.asStateFlow()


    fun start(scope: CoroutineScope){
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }
        scope.launch {
            var angle = 0f
            while (true) {
                kotlinx.coroutines.delay(50)

                val p = getPlayerState("Oleg")
                val mem = p.alchemistMemory

                if (!mem.isStopped) {
                    angle += 0.03f
                    val newX = -3f + kotlin.math.cos(angle) * 1.5f
                    val newZ =  0f + kotlin.math.sin(angle) * 1.5f

                    updatePlayer("Oleg") { pl ->
                        pl.copy(
                            alchemistMemory = mem.copy(
                                posX = newX,
                                posZ = newZ
                            )
                        )
                    }
                }
            }
        }
    }

    private fun setPlayerState(playerId: String, data: PlayerState){
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }
    fun getPlayerState(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }

    private fun updatePlayer(playerId: String, change: (PlayerState) -> PlayerState) {
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()
    }

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->  //> minBy = берёт ближайший объект по расстоянию до игрока | OrNull - если нет таких объектов - вернуть null
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerState(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (oldAreaId == newAreaId) {
            val newHint =
                when (newAreaId) {
                    "alchemist" -> "Подойди и нажми по алхимику"
                    "herb_source" -> "Собери траву"
                    "chest" -> "открой пжпжп"
                    else -> "Подойди к одной из локаций"
                }
            updatePlayer(playerId) {p -> p.copy(hintText = newHint)}
            return
        }

        if(oldAreaId != null) {
            _events.emit(LeftArea(playerId, oldAreaId)) //> emit - "Сообщи всем подписчикам, что произошло событие LeftArea"
        }

        if (newAreaId != null) {
            _events.emit(EnteredArea(playerId, newAreaId))

            if (newAreaId == "herb_source") {
                updatePlayer(playerId) { p ->
                    val memor = p.alchemistMemory
                    if (!memor.sawPlayerNearSource) {
                        p.copy(alchemistMemory = memor.copy(sawPlayerNearSource = true))
                    }else p
                }
            }

        }
        WorldObjectType
        val newHint =
            when (newAreaId) {
                "alchemist" -> "Подойди и нажми по алхимику"
                "herb_source" -> "Собери траву"
                "chest" -> "открой пжпжп"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) {p -> p.copy(hintText = newHint, currentAreaId = newAreaId)}
    }
    private suspend fun processCommand(cmd: GameCommand) {
        when (cmd) {
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId) { p ->
                    val mem = p.alchemistMemory
                    p.copy(
                        posX = p.posX + cmd.dx,
                        posZ = p.posZ + cmd.dz,
                        alchemistMemory = mem.copy(
                            isStopped = true
                        )
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }

            is CmdInteract -> {
                val player = getPlayerState(cmd.playerId) //!
                val obj = nearestObject(player)
                val dist = distance2d(player.posX, player.posZ, obj.x, obj.z)
                val herb = herbCount(player)

                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодейсвия"))
                    return
                }
                if (dist > obj.interactRadius) {
                    _events.emit(ServerMessage(cmd.playerId, "чел ты куда ушёл"))
                    return
                }

                when (obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1
                        )

                        if(herb < 3 && newMemory.sawPlayerNearSource){
                            DialogueView(
                                "Алхимик",
                                "а я тебя видел на herb source",
                                emptyList()
                            )
                        }


                        updatePlayer(cmd.playerId) {p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Трава тебе не надо щас, сначала квест"))
                            return
                        }

                        val oldCount = herbCount(player)
                        val found = (1..3).random()
                        val newCount = oldCount + found
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(ServerMessage(cmd.playerId, "Ты нашёл $found травы"))
                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                    }

                    WorldObjectType.CHEST -> {
                        player.copy(gold = player.gold + 1)
                    }
                }
            }
            is CmdChooseDialogueOption -> {
                val player = getPlayerState(cmd.playerId)

                if (player.currentAreaId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Сначала подойди к алхимику"))
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if(player.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать ток в начале квеста"))
                            return
                        }

                        updatePlayer(cmd.playerId) { p ->
                            p.copy(
                                alchemistMemory = p.alchemistMemory.copy(
                                    isStopped = false
                                )
                            )
                        }
                        alchemistNode

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик попросил собрать 3 травы"))
                    }
                    "give_herb" ->{
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                        }

                        val herbs = herbCount(player)

                        if (herbs > 3){
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory = if(newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true
                        )

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(
                                inventory = newInventory,
                                gold = p.gold + 5,
                                questState = QuestState.GOOD_END,
                                alchemistMemory = newMemory
                            )
                        }

                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))
                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.GOOD_END))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик получил траву и выдал тебе золото"))
                    }
                    else -> {
                        _events.emit(ServerMessage(cmd.playerId,"Неизвестный формат диалога"))
                    }
                }
            }
            is CmdSwitchActivePlayer -> {
                //........................................................
            }

            is CmdResetPlayer -> {
                updatePlayer(cmd.playerId) {_ -> initialPlayerState(cmd.playerId)}
                _events.emit(ServerMessage(cmd.playerId, "Игрок сброшен к начальному состоянию"))
            }
        }
    }
}

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")
    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))
    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState): String{
    return if (player.inventory.isEmpty()){
        "Inventory: (пусто)"
    }else{
        "Inventory:" + player.inventory.entries.joinToString { "${it.key}: ${it.value}" }
    }
}

fun currentObjective(player: PlayerState): String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "подойди к алхимику и начни разговор"
        QuestState.WAIT_HERB -> {
            if (herbs < 3) "Собери 3 травы. сейчас $herbs /3"
            else "Вернись к алхимику и отдай 3 травы"
        }
        QuestState.GOOD_END -> "Квест завершён хорошо"
        QuestState.EVIL_END -> "Квест завершён плохо"
    }
}

fun currentZoneText(player: PlayerState): String{
    return when(player.currentAreaId){
        "alchemist" -> "зона: алхимик"
        "herb_source" -> "Зона источника травы"
        "chest" -> "Зона: Фундук"
        else -> "Без зоны :("
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "Встретился:${memory.hasMet} | Сколько раз поговорил: ${memory.timesTalked} | отдал траву: ${memory.receivedHerb}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is EnteredArea -> "EnteredArea: ${e.areaId}"
        is LeftArea -> "LeftArea: ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc: ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource: ${e.sourceId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged Встретился:${e.memory.hasMet} | Сколько раз поговорил: ${e.memory.timesTalked} | отдал траву: ${e.memory.receivedHerb}"
        is ServerMessage -> "Server: ${e.text}"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }

        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }
        herbNode.transform.translate(3f, 0f, 0f)

        val chestNode = addColorMesh {
            generate {
                cube{
                    colored()
                }
            }
            shader = KslPbrShader{
                color{vertexColor()}
                metallic(0f)
                roughness(0.25f)
            }
        }
        chestNode.transform.translate(1f, 0f, 0f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,1f))
            setColor(Color.YELLOW, 5f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate{
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerState(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)

            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        alchemistNode.onUpdate {
            val p = server.getPlayerState("Oleg")
            val mem = p.alchemistMemory
            transform.setIdentity()
            transform.translate(mem.posX, 0f, mem.posZ)
        }
        herbNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        chestNode.onUpdate{
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.players.map{ map ->
                    map[pid] ?: initialPlayerState(pid)
                }
            }
            .onEach { player ->
                hud.playerSnapShot.value = player
            }
            .launchIn(coroutineScope)
        hud.activePlayerIdFlow
            .flatMapLatest { pid ->
                server.events.filter {it.playerId == pid}
            }
            .map{event ->
                eventToText(event)
            }
            .onEach { line ->
                hudLog(hud, "[${hud.activePlayerIdFlow.value}] $line")
            }
            .launchIn(coroutineScope)
        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f,0f,0f,0.6f), (12.dp)))
                .padding(12.dp)
            Column {
                val player = hud.playerSnapShot.use()
                val dialogue = buildAlchemistDialogue(player)

                Text("Игрок: ${hud.activePlayerIdFlow.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Позиция x=${"%.1f".format(player.posX)} z=${"%.1f".format(player.posZ)}"){}
                Text("Quest State: ${player.questState}"){
                    modifier.font(sizes.smallText)
                }
                Text(currentObjective(player)){
                    modifier.font(sizes.smallText)
                }
                Text(formatInventory(player)){
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }
                Text("Gold: ${player.gold}"){
                    modifier.font(sizes.smallText)
                }
                Text("Hint: ${player.hintText}"){
                    modifier.font(sizes.smallText)
                }
                Text("Npc Memory: ${formatMemory(player.alchemistMemory)}"){
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }

                Row {
                    Button("Сменить игрока"){
                        modifier.margin(end=8.dp).onClick{
                            val newId = if (hud.activePlayerIdFlow.value == "Oleg") "Stas" else "Oleg"

                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }

                    Button("Сбросить игрока"){
                        modifier.onClick {
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }
                Text("Движение в мире:"){modifier.margin(top=sizes.gap)}

                Row{
                    Button("Лево"){
                        modifier.margin(end=8.dp).onClick{
                            server.trySend(CmdMovePlayer(player.playerId, dx= -0.5f, dz = 0f))
                        }
                    }
                    Button("Право"){
                        modifier.margin(end=8.dp).onClick{
                            server.trySend(CmdMovePlayer(player.playerId, dx= 0.5f, dz = 0f))
                        }
                    }
                    Button("Вперёд"){
                        modifier.margin(end=8.dp).onClick{
                            server.trySend(CmdMovePlayer(player.playerId, dx= 0f, dz = -0.5f))
                        }
                    }
                    Button("Назад"){
                        modifier.margin(end=8.dp).onClick{
                            server.trySend(CmdMovePlayer(player.playerId, dx= 0f, dz = 0.5f))
                        }
                    }
                }

                Text("Взаимодействия") { modifier.margin(top = sizes.gap) }
                Row{
                    Button("Потрогать ближайшего") {
                        modifier.margin(end=8.dp).onClick{
                            server.trySend(CmdInteract(player.playerId))
                        }
                    }
                }

                Text(dialogue.npcId) { modifier.margin(top = sizes.gap) }

                Text(dialogue.text) {modifier.margin(bottom = sizes.smallGap)}

                if (dialogue.options.isEmpty()){
                    Text("Нет доступных вариантов ответа"){
                        modifier.margin(top = sizes.gap).font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                }else{
                    Row{
                        for(option in dialogue.options){
                            Button(option.text){
                                server.trySend(
                                    CmdChooseDialogueOption(player.playerId, option.id)
                                )
                            }
                        }
                    }
                }
                Text("лог: "){modifier.margin(top=sizes.gap, bottom = sizes.gap)}

                for (line in hud.log.use()){
                    Text(line){modifier.font(sizes.smallText)}
                }

                // 1. сделать фиксированную траекторию движения npc
                // Если с ним взаимодействует игрок - он останавливается

                // extra. При сборе травы - сделать кнопку не подбора а поиска травы где с любым шансом мы найдём от 1 до 3х трав
            }
        }
    }
}
