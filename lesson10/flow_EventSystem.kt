package lesson10

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene
import de.fabmax.kool.math.FLT_EPSILON
import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*
import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time
import de.fabmax.kool.pipeline.ClearColorLoad
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.util.DynamicStruct
import de.fabmax.kool.modules.ui2.UiModifier.*
import de.fabmax.kool.physics.geometry.PlaneGeometry
import de.fabmax.kool.physics.vehicle.Vehicle
import de.fabmax.kool.util.checkIsReleased

import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

// Flow корутины
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

// импорты Serialization
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

import java.io.File


@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val poisonTicksLeft: Int,
    val attackCooldownMsLeft: Long,
    val attackSpeedBuffTicks: Int, // Новое поле для байта скорости
    val questState: String
)

//События игровые - Flow будет рассылать их всем системам
sealed interface GameEvent{
    val playerId: String
}

// Новое событие: команда отклонена
data class CommandRejected(
    override val playerId: String,
    val reason: String
): GameEvent

data class AttackPressed(
    override val playerId: String,
    val targetId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent

data class QuestStateChanged(
    override val playerId: String,
    val questId: String,
    val newState: String
): GameEvent
data class ChoiceSelected(
    override val playerId: String,
    val npcId: String,
    val choiceId: String
): GameEvent
data class PoisonApplied(
    override val playerId: String,
    val ticks: Int,
    val damagePerTick: Int,
    val intervalMs: Long
): GameEvent

// Новое событие: байт скорости атаки
data class AttackSpeedBuffApplied(
    override val playerId: String,
    val ticks: Int
): GameEvent

data class  TalkedToNpc(
    override val playerId: String,
    val npcId: String,
): GameEvent

data class SaveRequested(
    override val playerId: String,
): GameEvent

class GameServer {
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 64)
    // Дополнительный небольшой буфер, что Emit при рассылке событий чаще проходил не упираясь в ограничение буфера

    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow(
        mapOf(
            "Oleg" to PlayerSave("Oleg", 100, 0, 0, 0L, 0, "START"),
            "Stas" to PlayerSave("Stas", 100, 0, 0, 0L, 0, "START") // Исправил опечатку "Oleg" на "Stas"
        )
    )

    val players: StateFlow<Map<String, PlayerSave>> = _players.asStateFlow()

    fun tryPublish(event: GameEvent): Boolean {
        return _events.tryEmit(event)
    }

    suspend fun publish(event: GameEvent) {
        _events.emit(event)
    }

    fun updatePlayer(playerId: String, change: (PlayerSave) -> PlayerSave) {
        //change - функция, которая берёт старый PlayerSave и возращает новый

        val oldMap = _players.value
        val oldPlayer = oldMap[playerId] ?: return

        val newPlayer = change(oldPlayer)

        val newMap = oldMap.toMutableMap()
        newMap[playerId] = newPlayer

        _players.value = newMap
    }

    fun getPlayer(playerId: String): PlayerSave {
        return _players.value[playerId] ?: PlayerSave(playerId, 100, 0, 0, 0L, 0, "START")
    }
}

class  DamageSystem(
    private val server: GameServer
){
    fun onEvent(e: GameEvent){
        if(e is DamageDealt){
            server.updatePlayer(e.targetId){player -> // Урон получает targetId, а не playerId
                val newHp = (player.hp - e.amount).coerceAtLeast(0)
                player.copy(hp = newHp)
            }
        }
    }
}

class  CooldownSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val cooldownJobs = mutableMapOf<String, Job>()

    // Функция для получения текущей длительности кулдауна с учетом баффа
    fun getCooldownDuration(playerId: String, baseCooldown: Long): Long {
        val player = server.getPlayer(playerId)
        return if (player.attackSpeedBuffTicks > 0) {
            // Если есть бафф скорости, кулдаун меньше
            (baseCooldown * 0.583).toLong() // 700ms вместо 1200ms (примерно 58.3%)
        } else {
            baseCooldown
        }
    }

    fun startCooldown(playerId: String, baseTotalMs: Long){
        cooldownJobs[playerId]?.cancel()

        val actualCooldown = getCooldownDuration(playerId, baseTotalMs)
        println("Игрок $playerId: кулдаун $actualCooldown мс (базовый: $baseTotalMs, бафф: ${server.getPlayer(playerId).attackSpeedBuffTicks})")

        server.updatePlayer(playerId) {player -> player.copy(attackCooldownMsLeft = actualCooldown)}

        val job = scope.launch {
            val step = 100L

            while(isActive && server.getPlayer(playerId).attackCooldownMsLeft > 0L){
                delay(step)

                server.updatePlayer(playerId) {player ->
                    val left = (player.attackCooldownMsLeft - step).coerceAtLeast(0L)
                    player.copy(attackCooldownMsLeft = left)
                }
            }
        }
        cooldownJobs[playerId] = job
    }
    fun  canAttack(playerId: String): Boolean{
        return server.getPlayer(playerId).attackCooldownMsLeft <= 0L
    }
}

// Новая система для баффа скорости атаки
class AttackSpeedBuffSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val buffJobs = mutableMapOf<String, Job>()

    fun onEvent(e: GameEvent) {
        if (e is AttackSpeedBuffApplied) {
            // Отменяем предыдущий бафф, если был
            buffJobs[e.playerId]?.cancel()

            // Добавляем тики баффа к существующим
            server.updatePlayer(e.playerId) { player ->
                player.copy(attackSpeedBuffTicks = player.attackSpeedBuffTicks + e.ticks)
            }

            val job = scope.launch {
                while (isActive && server.getPlayer(e.playerId).attackSpeedBuffTicks > 0) {
                    delay(1000L) // Каждую секунду уменьшаем счетчик тиков
                    server.updatePlayer(e.playerId) { player ->
                        val newTicks = (player.attackSpeedBuffTicks - 1).coerceAtLeast(0)
                        player.copy(attackSpeedBuffTicks = newTicks)
                    }
                }
            }
            buffJobs[e.playerId] = job
        }
    }
}

class PoisonSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val poisonJobs = mutableMapOf<String, Job>()

    fun onEvent(e: GameEvent, publishDamage: (DamageDealt) -> Unit){
        if (e is PoisonApplied){
            poisonJobs[e.playerId]?.cancel()

            server.updatePlayer(e.playerId){ player ->
                player.copy(poisonTicksLeft = player.poisonTicksLeft + e.ticks)
            }
            val job = scope.launch{
                while (isActive && server.getPlayer(e.playerId).poisonTicksLeft > 0){
                    delay(e.intervalMs)

                    server.updatePlayer(e.playerId){ player ->
                        player.copy(poisonTicksLeft = (player.poisonTicksLeft - 1).coerceAtLeast(0))
                    }
                    publishDamage(DamageDealt(e.playerId, e.playerId, e.damagePerTick)) // Цель - сам игрок
                }
            }
            poisonJobs[e.playerId] = job
        }
    }
}

class QuestSystem(
    private val server: GameServer,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private val questId = "q_alchemist"
    private val npcId = "alchemist"

    fun onEvent(e: GameEvent, publish: (GameEvent) -> Unit){
        val player = server.getPlayer(e.playerId)

        when(e){
            is TalkedToNpc ->{
                if (e.npcId != npcId) return
                if (player.questState == "START"){
                    server.updatePlayer(e.playerId){it.copy(questState = "OFFERED")}
                    publish(QuestStateChanged(e.playerId, questId, "OFFERED"))
                }
            }
            is ChoiceSelected -> {
                if (e.npcId != npcId) return

                // Задание 1: Отказ, если игрок не в состоянии OFFERED
                if (player.questState != "OFFERED") {
                    publish(CommandRejected(e.playerId, "Нельзя выбрать помощь, пока квест не предложен!"))
                    return
                }

                if (player.questState == "OFFERED"){
                    val newState = if(e.choiceId == "help") "GOOD_END"
                    else "EVIL_END"

                    server.updatePlayer(e.playerId){it.copy(questState = newState)}
                    publish(QuestStateChanged(e.playerId, questId, newState))
                }
            }
            else -> {}
        }
    }
}

class SaveSystem{
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private fun file(playerId: String): File{
        val dir = File("saves")
        if(!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }
    fun save(player: PlayerSave){

        val text = json.encodeToString(PlayerSave.serializer(), player )

        file(player.playerId).writeText(text)
    }
    fun load(playerId: String): PlayerSave? {
        val file = file(playerId)
        if (!file.exists()) return null

        val text = file.readText()

        return try {
            json.decodeFromString(PlayerSave.serializer(), text)
        } catch (e: Exception) {
            println("Ошибка загрузки файла $playerId")
            null

        }
    }
}

class  HudState {
    val activePlayerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val poisonTicksLeft = mutableStateOf(0)
    val questState = mutableStateOf("START")
    val attackCooldownMsLeft = mutableStateOf(0L)
    val attackSpeedBuffTicks = mutableStateOf(0) // Для отображения баффа

    val log = mutableStateOf<List<String>>(emptyList())
}

fun hudLog(hud: HudState, line: String){
    hud.log.value = (hud.log.value + line). takeLast(20)
}

object  Shared{
    var server: GameServer? = null
    var saver: SaveSystem? = null
    var cooldowns: CooldownSystem? = null
    var quests: QuestSystem? = null
    var poison: PoisonSystem? = null
    var damage: DamageSystem? = null
    var attackSpeedBuff: AttackSpeedBuffSystem? = null // Добавили новую систему
}

fun main() = KoolApplication {
    val hud = HudState()

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

        // Обработчик для DamageSystem
        coroutineScope.launch {
            server.events.collect { event ->
                damage.onEvent(event)
            }
        }

        // Обработчик для PoisonSystem
        coroutineScope.launch {
            server.events.collect { event ->
                poison.onEvent(event) { dmg ->
                    if (!server.tryPublish(dmg)) {
                        coroutineScope.launch { server.publish(dmg) }
                    }
                }
            }
        }

        // Обработчик для QuestSystem (теперь может публиковать CommandRejected)
        coroutineScope.launch {
            server.events.collect { event ->
                quests.onEvent(event) { newEvent ->
                    if (!server.tryPublish(newEvent)) {
                        coroutineScope.launch { server.publish(newEvent) }
                    }
                }
            }
        }

        // Обработчик для AttackSpeedBuffSystem
        coroutineScope.launch {
            server.events.collect { event ->
                attackSpeedBuff.onEvent(event)
            }
        }

        // Обработчик для SaveSystem
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
            // Логирование событий в HUD
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
                        is CommandRejected -> "❌ Отказ: ${event.reason}" // Отображаем отказ
                        else -> "Неизвестное событие"
                    }
                    hudLog(hud, "$line")
                }
            }

            // Обновление HUD при изменении данных игрока
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
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)
                .width(300.dp) // Сделаем панель пошире

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
                // Кнопка переключения игрока
                Button("Switch Player (Oleg/Stas)") {
                    modifier.margin(bottom = 8.dp)
                        .onClick {
                        hud.activePlayerId.value =
                            if (hud.activePlayerId.value == "Oleg") "Stas" else "Oleg"
                    }
                }

                // Задание 2: Кнопка атаки
                Button("⚔️ Attack Goblin") {
                    modifier.margin(bottom = 8.dp)
                        .onClick {
                        val server = Shared.server ?: return@onClick
                        val playerId = hud.activePlayerId.value
                        val targetId = "Goblin" // Тестовая цель

                        // Проверяем, может ли игрок атаковать (кулдаун)
                        val cooldowns = Shared.cooldowns
                        if (cooldowns != null && !cooldowns.canAttack(playerId)) {
                            // Публикуем отказ, если кулдаун еще не прошел
                            val rejectEvent = CommandRejected(playerId, "Атака еще на перезарядке! Осталось ${hud.attackCooldownMsLeft.use()} мс")
                            if (!server.tryPublish(rejectEvent)) {
                                coroutineScope.launch { server.publish(rejectEvent) }
                            }
                            return@onClick
                        }

                        // 1. Публикуем событие AttackPressed
                        val attackEvent = AttackPressed(playerId, targetId)
                        val published = if (!server.tryPublish(attackEvent)) {
                            coroutineScope.launch { server.publish(attackEvent) }
                            true
                        } else {
                            true
                        }

                        if (published) {
                            // 2. Если атака разрешена, наносим урон
                            val damageEvent = DamageDealt(playerId, targetId, 15) // 15 урона
                            if (!server.tryPublish(damageEvent)) {
                                coroutineScope.launch { server.publish(damageEvent) }
                            }

                            // 3. Запускаем кулдаун (базовый 1200 мс, но система сама учтет бафф)
                            cooldowns?.startCooldown(playerId, 1200)
                        }
                    }
                }

                // Кнопка для тестирования баффа скорости атаки (Задание 3)
                Button("⚡ Speed Buff (5 sec)") {
                    modifier.margin(bottom = 8.dp)
                        .onClick {
                        val server = Shared.server ?: return@onClick
                        val playerId = hud.activePlayerId.value

                        val buffEvent = AttackSpeedBuffApplied(playerId, 5) // 5 тиков баффа
                        if (!server.tryPublish(buffEvent)) {
                            coroutineScope.launch { server.publish(buffEvent) }
                        }
                        hudLog(hud, "👟 Бафф скорости активирован для $playerId")
                    }
                }

                // Кнопки для тестирования квеста и отказа (Задание 1)
                Row {
                    modifier.margin(bottom = 8.dp)
                    Button("Talk to Alchemist") {
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
                    Button("Choose Help") {
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

                // Кнопка для тестирования яда
                Button("💀 Poison Self (3 ticks)") {
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

                // Кнопка сохранения
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

                Text("Log:") {
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
