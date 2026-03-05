package lesson4


import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.*                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*

import kotlin.random.Random
import lesson3.ItemType
import lesson2.ItemStack
import lesson3.HEALING_POTION
import lesson3.WOOD_SWORD
import lesson3.GameState
import lesson3.ItemAdded
import lesson3.putIntoSlot
import lesson3.useSelected
import java.io.File

data class  Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int
)

sealed interface GameEvent{
    val playerId: String
}

enum class ItemType{
    WEAPON,
    ARMOR,
    POTION
}
val WOOD_SWORD = Item(
    "sword_wood",
    "Wood sword",
    ItemType.WEAPON,
    1
)

data class QuestStepCompleted(
    override val playerId: String,
    val questId: String,
    val stepId: Int
): GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val questId: String,
    val stepId: Int
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class ItemUsed(
    override val playerId: String,
    val itemId: String
): GameEvent

data class  EffectApplied(
    override val playerId: String,
    val effectId: String,
    val ticks: Int
): GameEvent
data class SaveInfo(
    val fileName: String,
    val playerId: String,
    val questId: String,
    val stepId: Int,
    val hp: Int,
    val gold: Int,
    val filePath: File
)

typealias Listener = (GameEvent) -> Unit
class EventBus{

    // функция принимающая GameEvent возвращает пустоту

    private val listeners = mutableListOf<Listener>()

    fun subscribe(listener: Listener){
        listeners.add(listener)
    }

    fun publish(event: GameEvent){
        for(listener in listeners){
            listener(event)
        }
    }
}

class QuestSystem(
    private val bus: EventBus
){
    val questId = "q_training"
    val progressByPlayer = mutableStateOf<Map<String, Int>>(emptyMap())

    fun getStep(playerId: String): Int{
        return progressByPlayer.value[playerId] ?: 0
    }

    fun setStep(playerId: String, step: Int){
        val copy = progressByPlayer.value.toMutableMap()
        copy[playerId] = step
        progressByPlayer.value = copy.toMap()
    }

    fun completeStep(playerId: String, stepId: Int){
        val next = stepId + 1

        setStep(playerId, next)

        bus.publish(
            QuestStepCompleted(
                playerId,
                questId,
                stepId
            )
        )
        //публикуем сразу событие сохранения прогресса игрока
        // то есть этапы квеста - будут как контрольные точки в игре

        bus.publish(
            PlayerProgressSaved(
                playerId,
                questId,
                next
            )
        )
    }
}
class SaveSystem(
    private  val bus: EventBus,
    private val game: GameState,
    private val quest: QuestSystem
){
    init {
        bus.subscribe {
                event ->
            if (event is PlayerProgressSaved){
                // Ожидаем событие сохранения прогресса, и когда оно прилетит - пишем в файл
                saveProgress(event.playerId, event.questId, event.stepId)
            }
        }
    }
    private fun saveFile(playerId: String, questId: String): File{
        val dir = File("saves")
        if(!dir.exists()){
            dir.mkdirs() // mkdirs - создаёт папку (и родителей этой папки), если её нету
        }

        // Имя файла: saves/player_1_q_training.save
        return File(dir, "${playerId}_${questId}.save")
    }

    fun saveProgress(playerId: String, questId:String, stepId: Int){
        val f = saveFile(playerId, questId)

        // простое хранение сохранения в формате ключ = значение
        val text =
            "playerId=${playerId}\n" +
                    "questId=${questId}\n" +
                    "stepId=${stepId}\n" +
                    "hp=${game.hp.value}\n" +
                    "gold=${game.gold.value}\n"

        f.writeText(text) //writeText - записать в файл строку
    }
    fun getAllSaves(): List<SaveInfo> {
        val dir = File("saves")
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }

        val saves = mutableListOf<SaveInfo>()

        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".save")) {
                try {
                    val lines = file.readLines()
                    val map = mutableMapOf<String, String>()

                    for (line in lines) {
                        val parts = line.split("=")
                        if (parts.size == 2) {
                            map[parts[0]] = parts[1]
                        }
                    }

                    val playerId = map["playerId"] ?: "Unknown"
                    val questId = map["questId"] ?: "Unknown"
                    val stepId = map["stepId"]?.toIntOrNull() ?: 0
                    val hp = map["hp"]?.toIntOrNull() ?: 100
                    val gold = map["gold"]?.toIntOrNull() ?: 0

                    saves.add(
                        SaveInfo(
                            fileName = file.name,
                            playerId = playerId,
                            questId = questId,
                            stepId = stepId,
                            hp = hp,
                            gold = gold,
                            filePath = file
                        )
                    )
                } catch (e: Exception) {
                    // Пропускаем поврежденные файлы
                    println("Ошибка чтения файла сохранения ${file.name}: ${e.message}")
                }
            }
        }

        return saves.sortedByDescending { it.filePath.lastModified() }

    }
    fun getPlayerSaves(playerId: String): List<SaveInfo> {
        return getAllSaves().filter { it.playerId == playerId }
    }
    fun loadProgress(playerId: String, questId: String){
        val f = saveFile(playerId, questId)
        if (!f.exists()) return

        val lines = f.readLines()// чтение файла построчно

        val map = mutableMapOf<String, String>()

        for(line in lines){
            val parts = line.split("=") // split делит строку на части (=) здесь разделитель
            if (parts.size == 2){
                val key = parts[0]
                val value = parts[1]
                map[key] = value
            }
        }
        val loadedStep = map["stepId"]?.toIntOrNull() ?: 0
        // ?. - "если не null - то вызови toIntOrNull"
        //toIntOrNull - пытается превратить строку в Int, иначе null
        // ?: если получили null - вернуть 0.
        val loadedHp = map["hp"]?.toIntOrNull() ?: 100
        val loadedGold = map["gold"]?.toIntOrNull() ?: 0

        game.hp.value = loadedHp
        game.gold.value = loadedGold

        quest.setStep(playerId, loadedStep)
    }
    fun loadSpecificSave(saveInfo: SaveInfo) {
        val lines = saveInfo.filePath.readLines()
        val map = mutableMapOf<String, String>()

        for (line in lines) {
            val parts = line.split("=")
            if (parts.size == 2) {
                map[parts[0]] = parts[1]
            }
        }

        val loadedStep = map["stepId"]?.toIntOrNull() ?: 0
        val loadedHp = map["hp"]?.toIntOrNull() ?: 100
        val loadedGold = map["gold"]?.toIntOrNull() ?: 0

        game.hp.value = loadedHp
        game.gold.value = loadedGold
        game.playerId.value = saveInfo.playerId

        quest.setStep(saveInfo.playerId, loadedStep)
    }
}


fun pushLog(game: GameState, text: String){
    game.eventLog.value = (game.eventLog.value + text).takeLast(20)
}

fun main() = KoolApplication {
    val game = GameState()
    val bus = EventBus()
    val quest = QuestSystem(bus)
    val saves = SaveSystem(bus, game, quest)

    bus.subscribe { event ->
        val line = when (event) {
            is ItemAdded -> "ItemAdded: ${event.itemId} + ${event.countAdded} (осталось: ${event.leftOver})"
            is ItemUsed -> "ItemUsed: ${event.itemId}"
            is PlayerProgressSaved -> "Game Saved: ${event.questId} Step: ${event.stepId}"
            is DamageDealt -> "DamageDealt: ${event.amount} - ${event.targetId}"
            is EffectApplied -> "EffectApplied: ${event.effectId} + ${event.ticks}"
            is QuestStepCompleted -> "QuestStepCompleted: ${event.questId} шаг: ${event.stepId + 1}"
            else -> {}
        }

        pushLog(game, "[${event.playerId}] $line")
    }
    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader {
                color { vertexColor() }
                metallic(0.8f)
                roughness(0.2f)
            }
            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }
        var poisonTimeSec = 0f
        var regenTimeSec = 0f
        onUpdate {
            if (game.poisonTicksLeft.value > 0) {
                poisonTimeSec += Time.deltaT
                if (poisonTimeSec >= 1f) {
                    poisonTimeSec = 0f
                    game.poisonTicksLeft.value -= 1
                    game.hp.value = (game.hp.value - 2).coerceAtLeast(0)
                }
            } else {
                poisonTimeSec = 0f
            }
            if (game.regenTicksLeft.value > 0) {
                poisonTimeSec += Time.deltaT
                if (poisonTimeSec >= 1f) {
                    poisonTimeSec = 0f
                    game.regenTicksLeft.value += 1
                    game.hp.value = (game.hp.value + 1).coerceAtLeast(0)
                } else {
                    regenTimeSec = 0f
                }
            }
        }
    }
    addScene {
        setupUiScene(ClearColorLoad)
        // setupUiScene - явно указывает движку, что сцена у нас UI
        // ClearColorLoad - указывает, интерфейсу отображаться поверх всех сцен
        // Говорит: "наложить UI как слой поверх всех сцен и не обновлять каждую секунду

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Игрок: ${game.playerId.use()}") {}
                Text("HP: ${game.hp.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }
                val step = quest.progressByPlayer.use()[game.playerId.use()] ?: 0
                Text("Прогресс квеста: $step") {
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Выбранный слот: ${game.selectedSlot.use() + 1}") {
                    modifier.margin(bottom = sizes.gap)
                }
                // второе задание
                Row {
                    Button("Сменить игрока") {
                        modifier.margin(end = 8.dp).onClick {
                            game.playerId.value =
                                if (game.playerId.value == "Player") "Player_1" else "Player_2"
                        }
                    }
                    Button("Выбрать сохранение") {
                        modifier.margin(start = 8.dp).onClick {
                            val allSaves = saves.getPlayerSaves(game.playerId.value)
                            game.availableSaves.value = allSaves
                            game.showSavesMenu.value = true
                        }
                    }

                    Button("Загрузить последнее сохранение") {
                        modifier.onClick {
                            saves.loadProgress(game.playerId.value, quest.questId)
                            pushLog(game, "[${game.playerId.value}] Загрузил сохранение из квеста ${quest.questId}")
                        }
                    }
                }

                Row {
                    modifier.margin(top = sizes.smallGap)
                    Button("Получить меч(Шаг 0)") {
                        modifier.margin(end = 8.dp).onClick {
                            val pid = game.playerId.value
                            quest.completeStep(pid, stepId = 0)
                        }
                    }
                    Button("Ударить манекен(Шаг 1)") {
                        modifier.onClick {
                            val pid = game.playerId.value
                            quest.completeStep(pid, stepId = 1)
                        }
                    }
                }
                Text("Лог событий") {
                    modifier.margin(top = sizes.gap)

                }
                val lines = game.eventLog.use()

                Column {
                    modifier.margin(top = sizes.smallGap)

                    for (line in lines) {
                        Text(line) {
                            modifier.font(sizes.smallText)
                        }
                    }
                }
                //задание 1
                Row{
                    modifier.margin(top = sizes.smallGap)
                    Button("Ударить мечом") {
                        modifier.margin(end = 8.dp).onClick {
                            val pid = game.playerId.value
                            val targetId = "Манекен"
                            val baseDamage = Random.nextInt(8, 13)
                            val isCritical = Random.nextFloat() < 0.25f
                            val criticalMultiplier = if (isCritical) Random.nextInt(15, 21) / 10f else 1.0f
                            val finalDamage = (baseDamage * criticalMultiplier).toInt()
                            bus.publish(
                                DamageDealt(
                                    playerId = pid,
                                    targetId = targetId,
                                    amount = finalDamage
                                )
                            )

                            if (isCritical){

                                pushLog(game, "Нанесён крит. удар! ${finalDamage} урона")

                            }
                            else  {
                                pushLog(game, "Удар мечом. ${baseDamage}")
                            }
                        }
                    }

                }
            }
        }

    }
}

