package lesson3

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

enum class ItemType { WEAPON, ARMOR, POTION }

data class Item(
    val id: String,
    val name: String,
    val type: ItemType,
    val maxStack: Int
)

data class ItemStack(
    val item: Item,
    val count: Int
)

class GameState(){
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)

    val potionTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val dummyHp = mutableStateOf(50)

    val hotbar = mutableStateOf(
        List<ItemStack?>(9) {null}
    )
    val selectedSlot = mutableStateOf(0)

    val eventLog = mutableStateOf<List<String>>(emptyList())
}

val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    ItemType.POTION,
    12
)

val SWORD = Item(
    "sword",
    "Sword",
    ItemType.WEAPON,
    1
)

//////////<0>
val ARMOR = Item(
    "armor_basic",
    "Armor",
    ItemType.ARMOR,
    1
)
//////////</0>

sealed interface GameEvent{
    val playerId: String
}

data class ItemAdded(
    override val playerId: String,
    val itemId: String,
    val countAdded: Int,
    val leftOver: Int
): GameEvent

data class ItemUsed(
    override val playerId: String,
    val itemId: String
): GameEvent

data class DamageDealt(
    override val playerId: String,
    val targetId: String,
    val amount: Int
): GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectId: String,
    val ticks: Int
): GameEvent

data class QuestStepCompleted(
    override val playerId: String,
    val questId: String,
    val stepIndex: Int
): GameEvent

typealias Listener = (GameEvent) -> Unit

class EventBus{
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

    init{
        bus.subscribe { event ->
            handleEvent(event)
        }
    }

    private fun getStep(playerId: String): Int{
        return progressByPlayer.value[playerId] ?: 0
    }

    private fun setStep(playerId: String, step: Int){
        val newMap = progressByPlayer.value.toMutableMap()
        newMap[playerId] = step
        progressByPlayer.value = newMap.toMap()
    }

    private fun completeStep(playerId: String, stepIndex: Int){
        setStep(playerId, stepIndex + 1)
        bus.publish(
            QuestStepCompleted(
                playerId,
                questId,
                stepIndex
            )
        )
    }

    private fun handleEvent(event: GameEvent){
        val player = event.playerId
        val step = getStep(player)

        if (step >= 2) return

        when(event){
            is ItemAdded -> {
                if (step == 0 && event.itemId == SWORD.id){
                    completeStep(player, 0)
                }
            }
            is DamageDealt -> {
                if (step == 1 && event.targetId == "dummy" && event.amount >= 10){
                    completeStep(player, 1)
                }
            }
            else -> {}
        }
    }
}
fun putIntoSlot(
    slots: List<ItemStack?>,
    slotIndex: Int,
    item: Item,
    addCount: Int
): Pair<List<ItemStack?>, Int>{
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex]

    if (current == null){
        val count = minOf(addCount, item.maxStack)
        newSlots[slotIndex] = ItemStack(item, count)
        val leftOver = addCount - count
        return Pair(newSlots, leftOver)
    }

    if(current.item.id == item.id && item.maxStack > 1){
        val freeSpace = item.maxStack - current.count
        val toAdd = minOf(addCount, freeSpace)
        newSlots[slotIndex] = ItemStack(current.item, current.count + toAdd)
        val leftOver = addCount - toAdd
        return Pair(newSlots, leftOver)
    }

    return Pair(newSlots, addCount)
}

fun useSelected(
    slots: List<ItemStack?>,
    slotIndex: Int
): Pair<List<ItemStack?>, ItemStack?> {
    val newSlots = slots.toMutableList()
    val current = newSlots[slotIndex] ?: return Pair(newSlots, null)

    val newCount = current.count - 1

    if (newCount <= 0) {
        newSlots[slotIndex] = null
    } else {
        newSlots[slotIndex] = ItemStack(current.item, newCount)
    }
    return Pair(newSlots, current)
}

fun pushLog(game: GameState, text: String){
    val old = game.eventLog.value
    val updated = old + text
    game.eventLog.value = updated.takeLast(20)
}

fun main() = KoolApplication{
    val game = GameState()
    val bus = EventBus()
    val quests = QuestSystem(bus)

    bus.subscribe { event ->
        val line = when(event){
            is ItemAdded -> "ItemAdded: ${event.itemId} + ${event.countAdded} (осталось: ${event.leftOver})"
            is ItemUsed -> "ItemUsed: ${event.itemId}"
            is DamageDealt -> "DamageDealt: ${event.amount} - ${event.targetId}"
            is EffectApplied -> "EffectApplied: ${event.effectId} +${event.ticks}"
            is QuestStepCompleted -> "QuestStepCompleted: ${event.questId} шаг: ${event.stepIndex+1}"
            else -> {}
        }
        pushLog(game, "[${event.playerId}] $line")
    }

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube { colored() } }

            shader = KslPbrShader{
                color { vertexColor() }
                metallic(0.8f)
                roughness(0.3f)
            }

            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }

        lighting.singleDirectionalLight {
            setup(Vec3f(-1f,-1f,-1f))
            setColor(Color.WHITE, 5f)
        }

        var potionTimeSec = 0f
        var regenTimeSec = 0f

        onUpdate {
            if (game.potionTicksLeft.value > 0) {
                potionTimeSec += Time.deltaT
                if (potionTimeSec >= 1f) {
                    potionTimeSec = 0f

                    //////////<0>
                    val armorActive =
                        game.hotbar.value.getOrNull(8)?.item?.type == ItemType.ARMOR

                    val poisonDamage = if (armorActive) 1 else 2
                    //////////</0>

                    game.potionTicksLeft.value -= 1
                    game.hp.value = (game.hp.value - poisonDamage).coerceAtLeast(0)
                }
            } else {
                potionTimeSec = 0f
            }
            
            if (game.regenTicksLeft.value > 0) {
                regenTimeSec += Time.deltaT
                if (regenTimeSec >= 1f) {
                    regenTimeSec = 0f
                    game.regenTicksLeft.value -= 1
                    game.hp.value = (game.hp.value + 1).coerceAtLeast(0)
                }
            } else {
                regenTimeSec = 0f
            }
        }
    }
}
addScene {
    setupUiScene(ClearColorLoad)

    addPanelSurface {
        modifier
            .align(AlignmentX.Start, AlignmentY.Top)
            .margin(16.dp)
            .background(RoundRectBackground(Color(0f,0f,0f,0.6f), 14.dp))
            .padding(12.dp)

        Column {
            Text("Игрок: ${game.playerId.use()}"){ }
            Text("HP: ${game.hp.use()}"){
                modifier.margin(bottom = sizes.gap)
            }
            Text("Отравление: ${game.potionTicksLeft.use()} | Реген: ${game.regenTicksLeft.use()}"){ }
            Text("HP манекена: ${game.dummyHp.use()}"){
                modifier.margin(bottom = sizes.gap)
            }

            val progress = quests.progressByPlayer.use()[game.playerId.use()]
            val questText = when (progress){
                0 -> "Квест: найти меч"
                1 -> "Квест: Ударь манекен мечем"
                else -> "Квест: Выполнен"
            }

            Text(questText){
                modifier.margin(bottom = sizes.gap)
            }
        }
        
        Row {
            modifier.margin(top = 6.dp)
            val slots = game.hotbar.use()
            val select = game.selectedSlot.use()

            for (i in 0 until 9){
                val isSelected = (i == select)
                Box {
                    modifier
                        .size(44.dp, 44.dp)
                        .margin(end = 5.dp)
                        .background(
                            RoundRectBackground(
                                if (isSelected) Color(0.2f, 0.6f, 1f, 0.8f)
                                else Color(0f,0f,0f, 0.35f),
                                8.dp
                            )
                        )
                        .onClick {
                            game.selectedSlot.value = i
                        }

                    val stack = slots[i]
                    if (stack == null){
                        Text(" "){}
                    } else {
                        Column {
                            modifier.padding(6.dp)
                            Text("${stack.item.name}") {
                                modifier.font(sizes.smallText)
                            }
                            Text("x${stack.count}") {
                                modifier.font(sizes.smallText)
                            }
                        }
                    }
                }
            }
        }
        
        Row {
            modifier.margin(top = 6.dp)

            Button("зелье++") {
                modifier.margin(end = 8.dp).onClick {
                    val idx = game.selectedSlot.value
                    val pid = game.playerId.value
                    val (updated, left) =
                        putIntoSlot(game.hotbar.value, idx, HEALING_POTION, 6)
                    game.hotbar.value = updated
                    bus.publish(ItemAdded(pid, HEALING_POTION.id, 6, left))
                }
            }

            Button("меч++") {
                modifier.margin(end = 8.dp).onClick {
                    val idx = game.selectedSlot.value
                    val pid = game.playerId.value
                    val (updated, left) =
                        putIntoSlot(game.hotbar.value, idx, SWORD, 1)
                    game.hotbar.value = updated
                    bus.publish(ItemAdded(pid, SWORD.id, 1, left))
                }
            }
            
            //////////<0>
            Button("броня++") {
                modifier.margin(end = 8.dp).onClick {
                    val idx = game.selectedSlot.value
                    val pid = game.playerId.value
                    val (updated, left) =
                        putIntoSlot(game.hotbar.value, idx, ARMOR, 1)
                    game.hotbar.value = updated
                    bus.publish(ItemAdded(pid, ARMOR.id, 1, left))
                }
            }
            //////////</0>

            Button("Использовать то что выбрали") {
                modifier.onClick {
                    val idx = game.selectedSlot.value
                    val pid = game.playerId.value
                    val (updatedSlots, used) =
                        useSelected(game.hotbar.value, idx)
                    game.hotbar.value = updatedSlots

                    if (used != null) {
                        bus.publish(ItemUsed(pid, used.item.id))

                        if (used.item.type == ItemType.POTION) {
                            game.regenTicksLeft.value += 5
                            bus.publish(EffectApplied(pid, "regen", 5))
                        }
                    }
                }
            }
        }
        
        Row {
            modifier.margin(top = 6.dp)

            Button("Наложить яд") {
                modifier.onClick {
                    val pid = game.playerId.value
                    game.potionTicksLeft.value += 5
                    bus.publish(EffectApplied(pid, "potion", 5))
                }
            }
            
            //////////<0>
            val activeStack = game.hotbar.value[game.selectedSlot.value]
            val attackText =
                if (activeStack != null && activeStack.item.type == ItemType.WEAPON)
                    "Атакаь мечом"
                else
                    "Атака руками"

            Button(attackText) {
                modifier.onClick {
                    val idx = game.selectedSlot.value
                    val pid = game.playerId.value
                    val stack = game.hotbar.value[idx]

                    val dmg =
                        if (stack != null && stack.item.type == ItemType.WEAPON)
                            10
                        else
                            3

                    game.dummyHp.value =
                        (game.dummyHp.value - dmg).coerceAtLeast(0)

                    bus.publish(DamageDealt(pid, "dummy", dmg))
                }
            }
            //////////</0>

            Button("Смена перса") {
                modifier.margin(start = 8.dp).onClick {
                    game.playerId.value =
                        if (game.playerId.value == "Player") "Oleg" else "Player"
                }
            }
        }
        
        Text("Лог событий:") {
            modifier.margin(top = sizes.gap)
        }

        val logLines = game.eventLog.use()
        for (line in logLines) {
            Text(line) { modifier.font(sizes.smallText) }
        }
    }
}
}
