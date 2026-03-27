package realGaneScene

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
import kotlin.math.sin
import kotlin.math.cos

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
import kotlinx.coroutines.flow.combine

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
    CHEST
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
    val sawPlayerNearSource: Boolean = false,  // видел ли игрок источник травы
    val isInteracting: Boolean = false  // флаг, остановлен ли NPC из-за взаимодействия
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
                hasMet = true,
                timesTalked = 2,
                receivedHerb = false,
                sawPlayerNearSource = false,
                isInteracting = false
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
                hasMet = false,
                timesTalked = 0,
                receivedHerb = false,
                sawPlayerNearSource = false,
                isInteracting = false
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
                } else if (memory.sawPlayerNearSource) {
                    "Вижу, ты хотя бы дошёл до места, где растёт трава, ты ее принес?"
                } else {
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
                    "Мало, мне надо 3 травы",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "спс",
                    listOf(
                        DialogueOption("give_herb", "Отдать 3 травы")
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
    val sourceId: String,
    val herbsGathered: Int
): GameEvent

data class InteractedWithChest(
    override val playerId: String,
    val chestId: String
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

data class NpcPositionChanged(
    override val playerId: String,
    val npcId: String,
    val x: Float,
    val z: Float
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
            "treasure_box",
            WorldObjectType.CHEST,
            0f,
            4f,
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

    private val _npcState = MutableStateFlow(NpcState(
        id = "alchemist",
        x = -3f,
        z = 0f,
        angle = 0f,
        radius = 2.5f,
        speed = 1.2f,
        isMoving = true
    ))
    val npcState: StateFlow<NpcState> = _npcState.asStateFlow()

    fun start(scope: kotlinx.coroutines.CoroutineScope){
        scope.launch {
            commands.collect { cmd ->
                processCommand(cmd)
            }
        }


        scope.launch {
            while (scope.isActive) {
                delay(16)
                updateNpcMovement()
            }
        }
    }

    private fun updateNpcMovement() {
        val currentState = _npcState.value
        val anyPlayerInteracting = _players.value.values.any {
            it.alchemistMemory.isInteracting && it.currentAreaId == "alchemist"
        }

        if (anyPlayerInteracting) {
            if (currentState.isMoving) {
                _npcState.value = currentState.copy(isMoving = false)
            }
            return
        }

        val movingState = if (!currentState.isMoving) {
            currentState.copy(isMoving = true)
        } else {
            currentState
        }

        val newAngle = movingState.angle + movingState.speed * (1f/60f)
        val newX = movingState.centerX + movingState.radius * cos(newAngle)
        val newZ = movingState.centerZ + movingState.radius * sin(newAngle)

        _npcState.value = movingState.copy(
            x = newX.toFloat(),
            z = newZ.toFloat(),
            angle = newAngle
        )

        _events.tryEmit(NpcPositionChanged("system", "alchemist", newX.toFloat(), newZ.toFloat()))
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
        val npcPos = _npcState.value
        val objectsWithCurrentPos = worldObjects.map { obj ->
            if (obj.id == "alchemist") {
                obj.copy(x = npcPos.x, z = npcPos.z)
            } else {
                obj
            }
        }

        val candidates = objectsWithCurrentPos.filter { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2d(player.posX, player.posZ, obj.x, obj.z)
        }
    }

    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerState(playerId)
        val nearest = nearestObject(player)

        val oldAreaId = player.currentAreaId
        val newAreaId = nearest?.id

        if (newAreaId == "herb_source") {
            val currentMemory = player.alchemistMemory
            if (!currentMemory.sawPlayerNearSource) {
                val newMemory = currentMemory.copy(sawPlayerNearSource = true)
                updatePlayer(playerId) { p ->
                    p.copy(alchemistMemory = newMemory)
                }
                _events.emit(NpcMemoryChanged(playerId, newMemory))
            }
        }

        if (oldAreaId == newAreaId) {
            val newHint =
                when (newAreaId) {
                    "alchemist" -> "Подойди и нажми по алхимику"
                    "herb_source" -> "Собери траву"
                    "treasure_box" -> "Открой сундук"
                    else -> "Подойди к одной из локаций"
                }
            updatePlayer(playerId) {p -> p.copy(hintText = newHint)}
            return
        }

        if(oldAreaId != null) {
            _events.emit(LeftArea(playerId, oldAreaId))
        }

        if (newAreaId != null) {
            _events.emit(EnteredArea(playerId, newAreaId))
        }

        val newHint =
            when (newAreaId) {
                "alchemist" -> "Подойди и нажми по алхимику"
                "herb_source" -> "Собери траву"
                "treasure_box" -> "Открой сундук"
                else -> "Подойди к одной из локаций"
            }
        updatePlayer(playerId) {p -> p.copy(hintText = newHint, currentAreaId = newAreaId)}
    }

    private suspend fun processCommand(cmd: GameCommand) {
        when (cmd) {
            is CmdMovePlayer -> {
                updatePlayer(cmd.playerId) { p ->
                    p.copy(posX = p.posX + cmd.dx, posZ = p.posZ + cmd.dz)
                }
                refreshPlayerArea(cmd.playerId)
            }
            is CmdInteract -> {
                val player = getPlayerState(cmd.playerId)
                val obj = nearestObject(player)

                if (obj == null){
                    _events.emit(ServerMessage(cmd.playerId, "Рядом нет объектов для взаимодействия"))
                    return
                }

                when (obj.type){
                    WorldObjectType.ALCHEMIST -> {
                        val oldMemory = player.alchemistMemory
                        val newMemory = oldMemory.copy(
                            hasMet = true,
                            timesTalked = oldMemory.timesTalked + 1,
                            isInteracting = true
                        )

                        updatePlayer(cmd.playerId) {p ->
                            p.copy(alchemistMemory = newMemory)
                        }

                        _events.emit(InteractedWithNpc(cmd.playerId, obj.id))
                        _events.emit(NpcMemoryChanged(cmd.playerId, newMemory))

                        kotlinx.coroutines.GlobalScope.launch {
                            delay(3000)
                            val currentPlayer = getPlayerState(cmd.playerId)
                            if (currentPlayer.currentAreaId != "alchemist") {
                                // Если игрок ушел, возвращаем движение
                                val resetMemory = currentPlayer.alchemistMemory.copy(isInteracting = false)
                                updatePlayer(cmd.playerId) { p ->
                                    p.copy(alchemistMemory = resetMemory)
                                }
                            }
                        }
                    }

                    WorldObjectType.HERB_SOURCE -> {
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Трава тебе не надо щас, сначала квест"))
                            return
                        }

                        val herbsGathered = (0..3).random()
                        val oldCount = herbCount(player)
                        val newCount = oldCount + herbsGathered
                        val newInventory = player.inventory + ("herb" to newCount)

                        updatePlayer(cmd.playerId){ p->
                            p.copy(inventory = newInventory)
                        }

                        _events.emit(InteractedWithHerbSource(cmd.playerId, obj.id, herbsGathered))
                        _events.emit(InventoryChanged(cmd.playerId, "herb", newCount))

                        val message = if (herbsGathered == 0) {
                            "Ты ничего не нашел, трава не растет здесь"
                        } else {
                            "Ты собрал $herbsGathered травы! Всего у тебя $newCount"
                        }
                        _events.emit(ServerMessage(cmd.playerId, message))
                    }

                    WorldObjectType.CHEST -> {
                        val newGold = player.gold + 1
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(gold = newGold)
                        }
                        _events.emit(InteractedWithChest(cmd.playerId, obj.id))
                        _events.emit(ServerMessage(cmd.playerId, "Ты открыл сундук и нашел 1 золото! Теперь у тебя $newGold золота"))
                    }
                }
            }
            is CmdChooseDialogueOption -> {
                val player = getPlayerState(cmd.playerId)

                if (player.currentAreaId != "alchemist"){
                    _events.emit(ServerMessage(cmd.playerId, "Ты отошёл слишком далеко от Алхимика"))
                    val resetMemory = player.alchemistMemory.copy(isInteracting = false)
                    updatePlayer(cmd.playerId) { p ->
                        p.copy(alchemistMemory = resetMemory)
                    }
                    return
                }

                when(cmd.optionId){
                    "accept_help" -> {
                        if(player.questState != QuestState.START){
                            _events.emit(ServerMessage(cmd.playerId, "Путь помощи можно выбрать ток в начале квеста"))
                            return
                        }

                        updatePlayer(cmd.playerId){ p ->
                            p.copy(questState = QuestState.WAIT_HERB)
                        }

                        _events.emit(QuestStateChanged(cmd.playerId, QuestState.WAIT_HERB))
                        _events.emit(ServerMessage(cmd.playerId, "Алхимик попросил собрать 3 травы"))

                        val newMemory = player.alchemistMemory.copy(isInteracting = false)
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }
                    }
                    "give_herb" ->{
                        if (player.questState != QuestState.WAIT_HERB){
                            _events.emit(ServerMessage(cmd.playerId, "Сейчас нельзя сдать траву"))
                            return
                        }

                        val herbs = herbCount(player)

                        if (herbs < 3){
                            _events.emit(ServerMessage(cmd.playerId, "Недостаточно травы"))
                            return
                        }

                        val newCount = herbs - 3
                        val newInventory = if(newCount <= 0) player.inventory - "herb" else player.inventory + ("herb" to newCount)

                        val newMemory = player.alchemistMemory.copy(
                            receivedHerb = true,
                            isInteracting = false
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
                        val newMemory = player.alchemistMemory.copy(isInteracting = false)
                        updatePlayer(cmd.playerId) { p ->
                            p.copy(alchemistMemory = newMemory)
                        }
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

data class NpcState(
    val id: String,
    var x: Float,
    var z: Float,
    val angle: Float,
    val radius: Float = 2.5f,
    val centerX: Float = -3f,
    val centerZ: Float = 0f,
    val speed: Float = 1.2f,
    val isMoving: Boolean = true
)

class HudState{
    val activePlayerIdFlow = MutableStateFlow("Oleg")
    val activePlayerIdUi = mutableStateOf("Oleg")
    val playerSnapShot = mutableStateOf(initialPlayerState("Oleg"))
    val log = mutableStateOf<List<String>>(emptyList())
    val npcPosX = mutableStateOf(-3f)
    val npcPosZ = mutableStateOf(0f)
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line).takeLast(20)
}

fun formatInventory(player: PlayerState): String{
    return if (player.inventory.isEmpty()){
        "Inventory: (пусто)"
    }else{
        "Inventory: " + player.inventory.entries.joinToString { "${it.key}: ${it.value}" }
    }
}

fun currentObjective(player: PlayerState): String{
    val herbs = herbCount(player)

    return when(player.questState){
        QuestState.START -> "Подойди к алхимику и начни разговор"
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
        "alchemist" -> "Зона Алхимика"
        "herb_source" -> "Зона источника травы"
        "treasure_box" -> "Зона сундука"
        else -> "Без зоны"
    }
}

fun formatMemory(memory: NpcMemory): String{
    return "Встретился = ${memory.hasMet}, Говорил = ${memory.timesTalked}, Отдал траву = ${memory.receivedHerb}, Видел источник = ${memory.sawPlayerNearSource}, NPC двигается = ${!memory.isInteracting}"
}

fun eventToText(e: GameEvent): String{
    return when(e){
        is EnteredArea -> "Entered area ${e.areaId}"
        is LeftArea -> "Left area ${e.areaId}"
        is InteractedWithNpc -> "InteractedWithNpc ${e.npcId}"
        is InteractedWithHerbSource -> "InteractedWithHerbSource ${e.sourceId} | Собрано травы: ${e.herbsGathered}"
        is InteractedWithChest -> "InteractedWithChest ${e.chestId}"
        is InventoryChanged -> "InventoryChanged ${e.itemId} -> ${e.newCount}"
        is QuestStateChanged -> "QuestStateChanged ${e.newState}"
        is NpcMemoryChanged -> "NpcMemoryChanged: ${formatMemory(e.memory)}"
        is ServerMessage -> "Server: ${e.text}"
        is NpcPositionChanged -> "NPC position: (${"%.2f".format(e.x)}, ${"%.2f".format(e.z)})"
    }
}

fun main() = KoolApplication {
    val hud = HudState()
    val server = GameServer()

    addScene {
        defaultOrbitCamera()

        val playerNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }

        val alchemistNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        alchemistNode.transform.translate(-3f, 0f, 0f)

        val herbNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        herbNode.transform.translate(3f, 0f, 0f)

        val chestNode = addColorMesh {
            generate {
                cube {
                    colored()
                }
            }
            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0f)
                roughness(0.25f)
            }
        }
        chestNode.transform.translate(0f, 0f, 4f)

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }

        server.start(coroutineScope)

        var lastRenderedX = 0f
        var lastRenderedZ = 0f

        playerNode.onUpdate {
            val activeId = hud.activePlayerIdFlow.value
            val player = server.getPlayerState(activeId)

            val dx = player.posX - lastRenderedX
            val dz = player.posZ - lastRenderedZ

            playerNode.transform.translate(dx, 0f, dz)
            lastRenderedX = player.posX
            lastRenderedZ = player.posZ
        }

        var lastNpcX = -3f
        var lastNpcZ = 0f

        alchemistNode.onUpdate {
            val npc = server.npcState.value
            val dx = npc.x - lastNpcX
            val dz = npc.z - lastNpcZ
            alchemistNode.transform.translate(dx, 0f, dz)
            lastNpcX = npc.x
            lastNpcZ = npc.z
            hud.npcPosX.value = npc.x
            hud.npcPosZ.value = npc.z
            alchemistNode.transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }

        herbNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
        chestNode.onUpdate {
            transform.rotate(20f.deg * Time.deltaT, Vec3f.Y_AXIS)
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        coroutineScope.launch {
            server.players.collect { playersMap ->
                val currentPlayer = playersMap[hud.activePlayerIdFlow.value]
                if (currentPlayer != null) {
                    hud.playerSnapShot.value = currentPlayer
                }
            }
        }

        coroutineScope.launch {
            server.events.collect { event ->
                if (event.playerId == hud.activePlayerIdFlow.value) {
                    val text = eventToText(event)
                    hudLog(hud, "[${hud.activePlayerIdUi.value}] $text")
                } else if (event is NpcPositionChanged) {
                }
            }
        }

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            val player = hud.playerSnapShot.use()
            val dialogue = buildAlchemistDialogue(player)

            Column {
                Text("Игрок ${hud.activePlayerIdUi.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Позиция x=${"%.1f".format(player.posX)}  z=${"%.1f".format(player.posZ)}") {}
                Text("NPC позиция x=${"%.1f".format(hud.npcPosX.use())} z=${"%.1f".format(hud.npcPosZ.use())}") {
                    modifier.font(sizes.smallText)
                }
                Text("QuestState ${player.questState}") {
                    modifier.font(sizes.smallText)
                }
                Text(currentObjective(player)) {
                    modifier.font(sizes.smallText)
                }
                Text(formatInventory(player)) {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }
                Text("Gold ${player.gold}") {
                    modifier.font(sizes.smallText)
                }
                Text("Hint ${player.hintText}") {
                    modifier.font(sizes.smallText)
                }
                Text("NpcMemory ${formatMemory(player.alchemistMemory)}") {
                    modifier.font(sizes.smallText).margin(bottom = sizes.smallGap)
                }

                Row {
                    Button("Сменить игрока") {
                        modifier.margin(end = 8.dp).onClick {
                            val newId = if (hud.activePlayerIdUi.value == "Oleg") "Stas" else "Oleg"
                            hud.activePlayerIdUi.value = newId
                            hud.activePlayerIdFlow.value = newId
                        }
                    }
                    Button("Сбросить игрока") {
                        modifier.margin(end = 8.dp).onClick {
                            server.trySend(CmdResetPlayer(player.playerId))
                        }
                    }
                }

                Text("Движение: ") { modifier.margin(top = sizes.gap) }
                Row {
                    Button("Лево") {
                        modifier.margin(end = 8.dp).onClick {
                            val p = hud.playerSnapShot.value
                            server.trySend(CmdMovePlayer(p.playerId, dx = -0.5f, dz = 0f))
                        }
                    }
                    Button("Право") {
                        modifier.margin(end = 8.dp).onClick {
                            val p = hud.playerSnapShot.value
                            server.trySend(CmdMovePlayer(p.playerId, dx = 0.5f, dz = 0f))
                        }
                    }
                    Button("Вперёд") {
                        modifier.margin(end = 8.dp).onClick {
                            val p = hud.playerSnapShot.value
                            server.trySend(CmdMovePlayer(p.playerId, dx = 0f, dz = 0.5f))
                        }
                    }
                    Button("Назад") {
                        modifier.margin(end = 8.dp).onClick {
                            val p = hud.playerSnapShot.value
                            server.trySend(CmdMovePlayer(p.playerId, dx = 0f, dz = -0.5f))
                        }
                    }
                }

                Text("Взаимодействия") { modifier.margin(top = sizes.gap) }
                Row {
                    Button("Потрогать ближайшего") {
                        modifier.margin(end = 8.dp).onClick {
                            val p = hud.playerSnapShot.value
                            server.trySend(CmdInteract(p.playerId))
                        }
                    }
                }

                Text(dialogue.npcId) { modifier.margin(top = sizes.gap) }
                Text(dialogue.text) { modifier.margin(bottom = sizes.smallGap) }

                if (dialogue.options.isEmpty()) {
                    Text("Нет доступных вариантов ответа") {
                        modifier.font(sizes.smallText).margin(bottom = sizes.gap)
                    }
                } else {
                    Row {
                        for (option in dialogue.options) {
                            Button(option.text) {
                                modifier.margin(end = 8.dp).onClick {
                                    server.trySend(
                                        CmdChooseDialogueOption(
                                            player.playerId,
                                            option.id
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                Text("Log: ") { modifier.margin(top = sizes.gap) }
                for (line in hud.log.use()) {
                    Text(line) { modifier.font(sizes.smallText) }
                }

            }
        }
    }
}