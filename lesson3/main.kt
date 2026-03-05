package lesson3

import de.fabmax.kool.KoolApplication           // KoolApplication - запускает Kool-приложение (окно + цикл рендера)
import de.fabmax.kool.addScene                  // addScene - функция "добавь сцену" в приложение (у тебя она просила отдельный импорт)
import de.fabmax.kool.math.Vec3f                // Vec3f - 3D-вектор (x, y, z), как координаты / направление
import de.fabmax.kool.math.*                  // deg - превращает число в "градусы" (угол)
import de.fabmax.kool.scene.*                   // scene.* - Scene, defaultOrbitCamera, addColorMesh, lighting и т.д.
import de.fabmax.kool.modules.ksl.KslPbrShader  // KslPbrShader - готовый PBR-шейдер (материал)
import de.fabmax.kool.util.Color                // Color - цвет (RGBA)
import de.fabmax.kool.util.Time                 // Time.deltaT - сколько секунд прошло между кадрами
import de.fabmax.kool.pipeline.ClearColorLoad   // ClearColorLoad - режим: "не очищай экран, оставь то что уже нарисовано"
import de.fabmax.kool.modules.ui2.*             // UI2: addPanelSurface, Column, Row, Button, Text, dp, remember, mutableStateOf
import lesson2.Item
import lesson2.ItemStack
import lesson2.ItemType
import lesson2.WOOD_SWORD
import lesson4.SaveInfo

enum class ItemType{
    WEAPON,
    ARMOR,
    POTION
}


class  GameState{
    val playerId = mutableStateOf("Player")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)
    val poisonTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)
    val dummyHp = mutableStateOf(50)
    val hotbar = mutableStateOf(
        List<ItemStack?>(9){null}
        //список из 9 пустых ячеек хотбара
    )
    val selectedSlot = mutableStateOf(0)
    val eventLog = mutableStateOf<List<String>>(emptyList())
    val showSavesMenu = mutableStateOf(false)
    val availableSaves = mutableStateOf<List<SaveInfo>>(emptyList())
}
val HEALING_POTION = Item(
    "potion_heal",
    "Healing Potion",
    ItemType.POTION,
    12
)

val WOOD_SWORD = Item(
    "sword_wood",
    "Wood sword",
    ItemType.WEAPON,
    1
)
val WOOD_HELMET = Item(
    "helmet_wood",
    "Wood helmet",
    ItemType.ARMOR,
    1
)
sealed interface GameEvent{
    val playerId: String
}

// события для квестов и логов
// data class - просто удобство, он хранит данные как пакет и автоматически применяет toString

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
    val amount: Int,
): GameEvent

data class EffectApplied(
    override val playerId: String,
    val effectId: String,
    val tick: Int,
): GameEvent

data class QuestStepCompleted(
    override val playerId: String,
    val questId: String,
    val stepIndex: Int,
): GameEvent

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
fun putIntoSlot(
    slots: List<ItemStack?>,  // принимает текущие слоты инвентаря
    slotIndex: Int,   // Индекс слота, в который мы кладём предмет
    item: Item,
    addCount: Int
): Pair<List<ItemStack?>, Int>{
    // Pair передаёт:
    // 1 - новый изменённый список слотов (но с уже положенным в него предметом)
    // 2 - число, сколько предметов НЕ ВЛЕЗЛО В ЯЧЕЙКУ(остаток)

    val newSlots = slots.toMutableList()
    // копия списка для изменений

    val current =  newSlots[slotIndex]
    // current - сохраняем, информацию о том что сейчас лежит в слоте
    if (current == null){
        val countToPlace = minOf(addCount, item.maxStack)
        //
        newSlots[slotIndex] = ItemStack(item, countToPlace)

        val leftOver = addCount - countToPlace
        // сколько ещё предметов не влезло
        return Pair(newSlots, leftOver)
    }
    // Если слот не пустой и предмет, что в нем лежит совпадает по id с тем, который мы в него кладём
    // и если maxStack > 1
    if (current.item.id == item.id && item.maxStack > 1){
        val freeSpace = item.maxStack - current.count
        val toAdd = minOf(addCount, freeSpace)

        newSlots[slotIndex] = ItemStack(item, current.count + toAdd)

        val leftOver = addCount - toAdd
        return Pair(newSlots, addCount)

    }
    return Pair(newSlots, addCount)
    // Если предмет ни один не стакается - ничего не меняем, возвращаем всё как было

}
fun useSelected(
    slots: List<lesson2.ItemStack?>,
    slotIndex: Int
): Pair<List<lesson2.ItemStack?>, lesson2.ItemStack?>{
    //Pair - создаёт пару значений (новые слоты, и что использовали уже в слотах)
    val  newSlots = slots.toMutableList()
    val current = newSlots[slotIndex] ?: return  Pair(newSlots, null)

    val newCount = current.count - 1

    if(newCount <= 0){
        //Если слот после использования предмета стал пуст
        newSlots[slotIndex] = null
    }else{
        newSlots[slotIndex] = ItemStack(current.item, newCount)
        // Eсли после использования предмета стак не закончился - обновляем стак
    }
    return Pair(newSlots, current)
}
fun main() = KoolApplication {
    val game = GameState ()

    addScene {
        defaultOrbitCamera()

        addColorMesh {
            generate { cube{colored()} }

            shader = KslPbrShader{
                color {vertexColor()}
                metallic (0.8f)
                roughness (0.2f)
            }
            onUpdate{
                transform.rotate(45f.deg * Time.deltaT, Vec3f.Z_AXIS)
            }
        }
        lighting.singleDirectionalLight {
            setup(Vec3f(-1f, -1f,-1f))
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
        addScene {
            setupUiScene(ClearColorLoad)
            // setupUiScene - явно указывает движку, что сцена у нас UI
            // ClearColorLoad - указывает, интерфейсу отображаться поверх всех сцен
            // Говорит: "наложить UI как слой поверх всех сцен и не обновлять каждую секунду

            addPanelSurface {
                modifier
                    .align(AlignmentX.Start, AlignmentY.Top)
                    .margin(16.dp)
                    .background(RoundRectBackground(Color(0f,0f,0f,0.6f), 14.dp))
                    .padding(12.dp)

                Column{
                    Text("Игрок: ${game.playerId.use()}"){}
                    Text ( "HP: ${game.hp.use()} Золото: ${game.gold.use()}") {
                        modifier.margin(bottom = sizes.gap)
                    }
                    Text("Игрок: ${game.poisonTicksLeft.use()}"){}
                    Text("Игрок: ${game.dummyHp.use()}"){
                        modifier.margin(bottom = sizes.gap)
                    }

                    val slots = game.hotbar.use()
                    val selected = game.selectedSlot.use()
                    Row {
                        modifier.margin(bottom = sizes.smallGap)

                        for (i in 0 until 9){
                            val isSelected = (i == selected)

                            Box{
                                modifier
                                    .size(44.dp, 44.dp)
                                    .margin(end = 6.dp)
                                    .background(
                                        RoundRectBackground(
                                            if (isSelected){
                                                Color (0.2f, 0.6f, 1f, 0.8f )
                                            } else {
                                                Color(0f,0f,0f,0.8f)
                                            },
                                            8.dp
                                        )
                                    )
                                    .onClick{
                                        game.selectedSlot.value = i
                                    }
                                //номера слотов
                                Text  ("${i + 1}"){
                                    modifier
                                        .padding(4.dp)
                                        .font(sizes.smallText)
                                }
                                val stack = slots[i]
                                if (stack !=null){
                                    Column{
                                        modifier.padding(top = 18.dp, start = 6.dp)
                                        Text(stack.item.name) {
                                            modifier.font(sizes.smallText)
                                        }
                                        Text ("x${stack.count}"){
                                            modifier.font(sizes.smallText)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    //отображение отладочного текста
                    val selectedStack = slots[selected]
                    Text(
                        if(selectedStack == null) "Выбрано: (пусто)"
                        else "Выбрано: ${selectedStack.item.name} x${selectedStack.count}"
                    ){
                        modifier.margin(top = sizes.gap, bottom = sizes.gap)
                    }
                    Row {
                        modifier.margin(top = sizes.smallGap)

                        Button ("Получить зелье"){
                            modifier
                                .margin(end = 8.dp)
                                .onClick{
                                    val idx = game.selectedSlot.value

                                    val (updatedSlots, leftOver) =
                                        putIntoSlot(game.hotbar.value, idx, HEALING_POTION, 6)
                                    //вернул пару значений( 1 - новые слоты с предметом, 2 - число предметов, что не поместились)

                                    game.hotbar.value = updatedSlots
                                    // Присваиваем новый список -> UI увидит изменение состояния и обновится
                                    if (leftOver > 0){
                                        game.gold.value += leftOver
                                    }
                                }
                        }
                        Button ("Получить меч"){
                            modifier
                                .margin(end = 8.dp)
                                .onClick{
                                    val idx = game.selectedSlot.value

                                    val (updatedSlots, leftOver) =
                                        putIntoSlot(game.hotbar.value, idx, WOOD_SWORD, 1)
                                    //вернул пару значений( 1 - новые слоты с предметом, 2 - число предметов, что не поместились)

                                    game.hotbar.value = updatedSlots
                                    // Присваиваем новый список -> UI увидит изменение состояния и обновится
                                    if (leftOver > 0){
                                        game.gold.value += 1
                                    }
                                }
                        }
                        Button ("Получить броню"){
                            modifier
                                .margin(end = 8.dp)
                                .onClick{
                                    val idx = game.selectedSlot.value
                                    val (updatedSlots, leftOver) =
                                        putIntoSlot(game.hotbar.value, idx, WOOD_SWORD, 1)
                                    //вернул пару значений( 1 - новые слоты с предметом, 2 - число предметов, что не поместились)

                                    game.hotbar.value = updatedSlots
                                    // Присваиваем новый список -> UI увидит изменение состояния и обновится
                                    if (leftOver > 0){
                                        game.gold.value += 1
                                    }
                                }
                        }
                    }
                    Row{
                        modifier.margin(top = sizes.smallGap)

                        Button("Использовать предмет"){
                            modifier.onClick{
                                val idx = game.selectedSlot.value
                                val (updatedSlots, used) = useSelected(game.hotbar.value, idx)
                                game.hotbar.value = updatedSlots

                                if (used != null && used.item.type == ItemType.POTION){
                                    game.hp.value = (game.hp.value + 20).coerceAtMost(100)
                                }
                            }
                        }
                        Button (if (selectedStack == WOOD_SWORD){ "Атаковать мечом"}else{ "Атаковать рукой"}){
                            modifier.onClick{
                                val idx = game.selectedSlot.value
                                val stack = game.hotbar.value[idx]

                                if (stack != null && stack.item.type == ItemType.WEAPON){
                                    game.dummyHp.value = (game.dummyHp.value - 10).coerceAtLeast(0)
                                }else{
                                    game.dummyHp.value = (game.dummyHp.value - 3).coerceAtLeast(0)
                                }
                            }
                        }
                    }
                    Row{
                        modifier.margin(top = sizes.smallGap)

                        Button ("Наложить яд"){
                            modifier.onClick{
                                if(game.selectedSlot.value == 9)
                                    game.poisonTicksLeft.value += 5
                            }
                        }
                        Button ("Сбросить манекен"){
                            modifier
                                .margin(start = 5.dp)
                                .onClick{
                                    game.dummyHp.value = 50
                                }
                        }
                    }
                }
            }
        }
    }
}
//Cоздать броню - предмет
// СОздать кнопку, которая выдаёт броню
//Если броня лежит в слоте 9(последнем), то она считается активной,
// и яд наносит в 2 раза меньше урона

// Выбранный слот = активный предмет
// сделать, чтобы кнопка Attack показывала текст:
// " атаковать Мечом" - если в активном слоте лежит меч
// "Атаковать руками, если слот пуст или в нём что угодно, кроме типа предмета WEAPON"