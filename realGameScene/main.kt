package realGameScene

import PlayerData
import QuestSystem
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
import de.fabmax.kool.modules.ui2.UiModifier.*

import kotlinx.coroutines.launch  // запускает корутину
import kotlinx.coroutines.Job     // контроллер запущенной корутины
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive // проверка жива ли ещё корутина - полезно для циклов
import kotlinx.coroutines.delay

// Flow корутины
import kotlinx.coroutines.flow.MutableSharedFlow // табло состояний
import kotlinx.coroutines.flow.SharedFlow // Только чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow // Радиостанция событий
import kotlinx.coroutines.flow.StateFlow // Только для чтения стостояний
import kotlinx.coroutines.flow.asSharedFlow // Отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow // Отдать только StateFlow
import kotlinx.coroutines.flow.collect // Слушать поток

import kotlinx.coroutines.flow.filter // Оставляет в потоке только то что подходит по условию
import kotlinx.coroutines.flow.map  // Преобразует каждый элемент потока (например GameEvent -> String для логирования)
import kotlinx.coroutines.flow.onEach  // Делает нужное действие для каждого элемента в потоке но не изменяет сам поток
import kotlinx.coroutines.flow.launchIn  // Запускает слушателя на фоне в нужном пространстве работы корутин
import kotlinx.coroutines.flow.flatMapLatest // Нужно для переключения игроков
import kotlin.math.sqrt


enum class QuestState{
    START,
    WAIT_HERB,
    GOOD_END,
    EVIL_END
}

enum class WorldObjectType{
    ALCHEMIST,
    HERB_SOURCE
}

data class WorldObjectDef(
    val id: String,
    val type: WorldObjectType,
    val x: Float,
    val z: Float,
    val interactRadius: Float
)

data class NpcMemory(
    val hasMet: Boolean,
    val timesTalked: Int,
    val receiveHerb: Boolean
)

data class PlayerState(
    val playerId: String,
    val x: Float,
    val z: Float,
    val questState: QuestState,
    val inventory: Map<String, Int>,
    val alchemistMemory: NpcMemory,
    val currentAreId: String?,
    val hintText: String
)

fun herbCount(player: PlayerState): Int{
    return player.inventory["herb"] ?: 0
}

fun distance2d(ax: Float, az: Float, bx: Float, bz: Float): Float{
    // sqrt((dx * dx) + (dz *dz))
    val dx = ax - bx
    val dz = az - bz
    return sqrt(dx*dx + dz*dz)
}

fun initialPlayerState(playerId: String): PlayerState{
    return if (playerId == "Stas"){
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
            "Подойди к одной из локаций"
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
            "Подойди к одной из локаций"
        )
    }
}

data class DialogueOption(
    val id: String,
    val text: String
)

data class DialogueView(
    val nocId: String,
    val text: String,
    val option: List<DialogueOption>
)

fun buildAlchemistDialogue(player: PlayerState): DialogueView{
    val herbs = herbCount(player)
    val memory = player.alchemistMemory

    return when(player.questState){
        QuestState.START ->{
            val greeting =
                if (!memory.hasMet){
                    "О,привет ты кто"
                }else{
                    "Снова ты я тебя знаю бла бла бла"
                }
            DialogueView(
                "Алхимик",
                "$greeting \nХочешь помочь тащи траву",
                listOf(
                    DialogueOption("accept_help", "я принесу травы"),
                    DialogueOption("threat", "травы не будет давай товар")
                )
            )
        }

        QuestState.WAIT_HERB ->{
            if (herbs <3){
                DialogueView(
                    "Алхимик",
                    "Недостаточно мне нужно $herbs/4 травы",
                    emptyList()
                )
            }else{
                DialogueView(
                    "Алхимик",
                    "ХБ",
                    listOf(
                        DialogueOption("give_herb", "отдать 4 травы")
                    )
                )
            }
        }
        QuestState.GOOD_END -> {
            val text =
                if (memory.hasMet){
                    "Thanks"
                }else{
                    "Ты завершил квест"
                }
            DialogueView(
                "Алхимик",
                text,
                emptyList()
            )
        }
        QuestState.EVIL_END -> {
            val text =
                if (memory.hasMet) {
                    "БЕБЕБЕ ты забрал товар"
                } else {
                    "Ты завершил квест (evil end)"
                }

            DialogueView(
                "Алхимик",
                text,
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
    val dz: Float,
): GameCommand

data class CMdInteract(
    override val playerId: String,
): GameCommand
data class CmdChooseDialogueOption(
    override val playerId: String,
    val optionId: String
): GameCommand

data class CmdSwitchActivePlayer(
    override val playerId: String,
    val newPlayerId: String,
): GameCommand

data class CmdResetPlayer(
    override val playerId: String,
): GameCommand

sealed interface GameEvent{
    val playerId: String,
}

data class EnterArea(
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
    val newState: QuestState,
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
            "alhemist",
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

    fun start(scope: kotlinx.coroutines.CoroutineScope, questSystem: QuestSystem){
        scope.launch {
            commands.collect{cmd ->
                processCommand(cmd, questSystem)
            }
        }
    }
    private fun setPlayerData(playerId: String, data: PlayerState){
        val map = _players.value.toMutableMap()
        map[playerId] = data
        _players.value = map.toMap()
    }
    private fun getPlayerData(playerId: String): PlayerState {
        return _players.value[playerId] ?: initialPlayerState(playerId)
    }
    private fun updateplayer(playerId: String, change: (PlayerState) -> PlayerState){
        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()

        newMap[playerId] = newPlayer
        _players.value = newMap.toMap()

    }

    private fun nearestObject(player: PlayerState): WorldObjectDef? {
        val candidates = worldObjects.filter { obj ->
            distance2d(player.x, player.z, obj.x, obj.z) <= obj.interactRadius
        }

        return candidates.minByOrNull { obj ->
            distance2d(player.x, player.z, obj.x, obj.z)
        }
    }
    private suspend fun refreshPlayerArea(playerId: String){
        val player = getPlayerData(playerId)
        val nearst = nearestObject(player)

        val oldAreaId = player.currentAreId
        val newAreaId = nearst?.id

        if (oldAreaId == newAreaId){
            val newHint =
                when(newAreaId){
                    "alhemist" -> "подойти и нажми на алхимику"
                    "herb_source" -> "СОбери траву"
                    else -> "Подойди к одной из локаций"
                }
            updateplayer(playerId) {p -> p.copy(hintText = newHint)}
            return
        }

        if (oldAreaId != null){
            _events.emit(LeftArea(playerId, oldAreaId))
        }
        if (newAreaId != null){
            _events.emit(LeftArea(playerId, newAreaId))
        }
    }
    private suspend fun processCommand(cmd: GameCommand){
        when(cmd){
            is CmdMovePlayer -> {
                updateplayer(cmd.playerId) {p ->
                    p.copy(
                        x = p.x + cmd.dx,
                        z = p.z + cmd.dz
                    )
                }
                refreshPlayerArea(cmd.playerId)
            }
            is CMdInteract -> {

            }
        }
    }

}










