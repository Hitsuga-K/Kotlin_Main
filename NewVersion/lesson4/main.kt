package lesson4

import de.fabmax.kool.KoolApplication
import de.fabmax.kool.addScene

import de.fabmax.kool.math.Vec3f
import de.fabmax.kool.math.deg
import de.fabmax.kool.scene.*

import de.fabmax.kool.modules.ksl.KslPbrShader
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Time

import de.fabmax.kool.pipeline.ClearColorLoad

import de.fabmax.kool.modules.ui2.*

import lesson3.ItemType
import lesson3.Item
import lesson3.ItemStack
import lesson3.HEALING_POTION
import lesson3.SWORD
import lesson3.GameState
import lesson3.ItemAdded
import lesson3.pushLog
import lesson3.putIntoSlot
import lesson3.useSelected
import java.io.File

sealed interface GameEvent {
    val playerId: String
}

data class QuestStepCompleted(
    override val playerId: String,
    val questId: String,
    val stepId: Int
) : GameEvent

data class PlayerProgressSaved(
    override val playerId: String,
    val questId: String,
    val stepId: Int
) : GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
) : GameEvent   // ← исправлено

data class ItemUsed(
    override val playerId: String,
    val itemId: String
) : GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectId: String,
    val ticks: Int
) : GameEvent

typealias Listener = (GameEvent) -> Unit

class EventBus {
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

class QuestSystem(
    private val bus: EventBus
) {
    val questId = "q_training"
    val progressByPlayer = mutableStateOf<Map<String, Int>>(emptyMap())

    fun getStep(playerId: String): Int {
        return progressByPlayer.value[playerId] ?: 0
    }

    fun setStep(playerId: String, step: Int) {
        val copy = progressByPlayer.value.toMutableMap()
        copy[playerId] = step
        progressByPlayer.value = copy.toMap()
    }

    fun completeStep(playerId: String, stepId: Int) {
        val next = stepId + 1

        setStep(playerId, next)

        bus.publish(
            QuestStepCompleted(
                playerId,
                questId,
                stepId
            )
        )

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
    private val bus: EventBus,
    private val game: GameState,
    private val quest: QuestSystem
) {
    init {
        bus.subscribe { event ->
            if (event is PlayerProgressSaved) {
                saveProgress(event.playerId, event.questId, event.stepId)
            }
        }
    }

    private fun saveFile(playerId: String, questId: String): File {
        val dir = File("saves")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${playerId}_${questId}.save")
    }

    fun saveProgress(playerId: String, questId: String, stepId: Int) {
        val f = saveFile(playerId, questId)

        val text =
            "playerId=$playerId\n" +
                    "questId=$questId\n" +
                    "stepId=$stepId\n" +
                    "hp=${game.hp.value}\n" +
                    "gold=${game.gold.value}\n"

        f.writeText(text)
    }

    fun loadProgress(playerId: String, questId: String) {
        val f = saveFile(playerId, questId)
        if (!f.exists()) return

        val lines = f.readLines()
        val map = mutableMapOf<String, String>()

        for (line in lines) {
            val parts = line.split("=")
            if (parts.size == 2) {
                map[parts[0]] = parts[1]
            }
        }

        // применяем загруженные данные
        map["stepId"]?.toIntOrNull()?.let { quest.setStep(playerId, it) }
        map["hp"]?.toIntOrNull()?.let { game.hp.value = it }
        map["gold"]?.toIntOrNull()?.let { game.gold.value = it }
    }
}

fun pushLog(game: GameState, text: String) {
    game.eventLog.value = (game.eventLog.value + text).takeLast(20)
}

fun main() = KoolApplication {
    val game = GameState()
    val bus = EventBus()
    val quests = QuestSystem(bus)
    val saves = SaveSystem(bus, game, quests)

    bus.subscribe { event ->
        val line = when (event) {
            is ItemAdded -> "ItemAdded: ${event.itemId} + ${event.countAdded} (осталось: ${event.leftOver})"
            is ItemUsed -> "ItemUsed: ${event.itemId}"
            is PlayerProgressSaved -> "Game Saved: ${event.questId} Step: ${event.stepId}"
            is DamageDealt -> "DamageDealt: ${event.amount} - ${event.targetId}"
            is EffectApplied -> "EffectApplied: ${event.effectId} +${event.ticks}"
            is QuestStepCompleted -> "QuestStepCompleted: ${event.questId} шаг: ${event.stepId + 1}"
            else -> "Unknown event"   // ← исправлено
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
                roughness(0.3f)
            }

            onUpdate {
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f, -1f))
            setColor(Color.WHITE, 5f)
        }
    }

    addScene {
        setupUiScene(ClearColorLoad)

        addPanelSurface {
            modifier
                .align(AlignmentX.Start, AlignmentY.Top)
                .margin(16.dp)
                .background(RoundRectBackground(Color(0f, 0f, 0f, 0.6f), 14.dp))
                .padding(12.dp)

            Column {
                Text("Игрок: ${game.playerId.use()}") { }

                Text("HP: ${game.hp.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }

                val step = quests.progressByPlayer.use()[game.playerId.use()] ?: 0
                Text("Прогресс квеста: $step") {
                    modifier.margin(bottom = sizes.gap)
                }

                Text("Выбранный слот: ${game.selectedSlot.use() + 1}") {
                    modifier.margin(bottom = sizes.gap)
                }
            }

        }
    }
}
