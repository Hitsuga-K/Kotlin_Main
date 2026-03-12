package lesson8

// старая система событий EventBus на списках Listeners - нормально для стартовых примеров, но в будущем вызовет проблемы
// например чем больше наша программа, тем сложнее работать с подписками, отменами конкуренцией кто и когда слушает
// Для аналога есть Flow - это готовая стандартная система событий из Kotlin coroutines

// Прежняя система сохранений "key=value" вручную - быстро и удобно для дема, но в реальной игре превращается в ад
// Много полей - много ошибок - трудные миграции
// kotlinx.serialization позволяет сохранять объекты почти одной строкой в формат JSON (и обратно тоже)
// Json.encodeToString() / decodeFromString()

// -----------------------------------------------

// Flow
// пример
// Есть радиостанция - она пускает события, а слушатели подписываются и получают информацию о событиях
// во Flow есть два главных варианта
// 1. SharedFlow - наше радио событий
// Это как поток радиостанций, трансляции и тд - он существует, даже когда никто не слушает и раздаёт события всем подписчикам
//Аналогия с GameEvent  (ударил, квест обновился, нпс сказал ...)
// 2. StateFlow - табло состояний
// Это тоже поток, который хранит одно текущее состояние и раздаёт всем подписчикам последнее известное состояние
// Идеально для SeverState, PlayerState, QuestJournal .....

// ------------ сохранения через сериализацию --------------//

// Будем сохранять не строки вручную, а объект целиком
// PlayerData(hp, gold, ...) Это надёжнее и легко расширяемо (добавил поле - оно сразу попало в JSON)
// @Serializable - аннотация (пометка) "этот класс, который мы пометили, можно сохранить или загрузить"

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
import de.fabmax.kool.modules.ui2.UiModifier.*


// Flow корутины
import kotlinx.coroutines.launch // запуск корутины
import kotlinx.coroutines.flow.MutableSharedFlow // табло состояний
import kotlinx.coroutines.flow.SharedFlow // Только чтение для подписчиков
import kotlinx.coroutines.flow.MutableStateFlow // Радиостанция событий
import kotlinx.coroutines.flow.StateFlow // Только для чтения стостояний
import kotlinx.coroutines.flow.asSharedFlow // Отдать наружу только SharedFlow
import kotlinx.coroutines.flow.asStateFlow // Отдать только StateFlow
import kotlinx.coroutines.flow.collect // слушать поток

// импорты Serialization
import kotlinx.serialization.Serializable // Анотация, что можно сохранять
import kotlinx.serialization.encodeToString //запись в файл
import kotlinx.serialization.decodeFromString // Чтение файла
import kotlinx.serialization.json.Json // Формат файла Json




import java.io.File

// Cобытия игры создаём как раньше, но отправлять будем через Flow

sealed interface GameEvent{
    val playerId: String
}

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

data class PlayerProgressSaved(
    override val playerId: String,
    val reason: String
): GameEvent

@Serializable
data class PlayerSave(
    val playerId: String,
    val hp: Int,
    val gold: Int,
    val questStates: Map<String, String> // Карта состояний questId -> newState
)

class ServerWorld(
    initialPlayerId: String
){
    // mutableSharedFlow - мы будем класть и приостанавливать выполнение события, пока подписчик не освободится
    // replay = 0 - это означает, "не пересылать старые события, новым подписчикам"
    private  val _event = MutableSharedFlow<GameEvent>(0)

    val events: SharedFlow<GameEvent> = _event.asSharedFlow()
    // сохраняем только в режиме для чтения (изменить нельзя)
    private val _playerState = MutableStateFlow(
        PlayerSave(
            initialPlayerId,
            100,
            0,
            mapOf("q_training" to "START")
        )
    )
    val playerState: StateFlow<PlayerSave> = _playerState.asStateFlow()

    //Команды сервера
    fun dealDamage(playerId: String, targetId: String, amount: Int){
        val old = _playerState.value

        val newHp = (old.hp - amount).coerceAtLeast(0)

        _playerState.value = old.copy(hp = newHp)

    }
    fun setQuestState(playerId: String, questId: String, newState: String){
        val old = _playerState.value

        val newQuestState = old.questStates + (questId to newState)

        _playerState.value = old.copy(questStates = newQuestState)
    }

    suspend fun emitEvent(event: GameEvent){
        _event.emit(event)
        //emit - будет рассылать события всем подписчикам
        //emit может разослать событие не сразу, если подписчики медленные  (очередь потоков)
        // Готовим событие заранее и рассылаем его уже в корутине
    }
    suspend fun applyLoaded(playerSave: PlayerSave) {
        _playerState.value = playerSave

        emitEvent(PlayerProgressSaved(
           playerId = playerSave.playerId,
            reason = "загрузка из JSON файла"
        ))
    }
    // написать реальную загрузку с файла, которая будет записывать загруженную информацию из файла в состояние игрока
    //Создаёте метод server.applyLoaded(playerSave: PlayerSave), Который должен:
    // заменять старое состояние игрока (хп, золото, этап квеста) на загруженное
    // публикует PlayerProgressSaved с причиной = загрузки файла в ручную из JSON
}
//сериализация - сохранение данных в файл
class SaveSystem{
    // настройка формата сериализации
    // prettyprint - просто делает json красивым и читаемым структурно
    // encodedefaulse - значения по умолчанию тоже будут записываться в файл
    private val json = Json{
        prettyPrint = true
        encodeDefaults = true

    }
    private fun saveFile(playerId: String): File{
        var dir = File("saves")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$playerId.json")
    }

    fun save(player: PlayerSave){
        val text = json.encodeToString(player)

        saveFile(player.playerId).writeText(text)

    }
    
    fun load(playerId: String): PlayerSave? {
        val file = saveFile(playerId)
        if (!file.exists()) return null

        val text = file.readText()

        return try {
            json.decodeFromString<PlayerSave>(text)
        } catch (e: Exception) {
            println("Ошибка загрузки файла $playerId")
             null

        }
    }
}
class UiState{
    //Состояние внутри него, будут обновляться от серверных данных

    val activePlayerId = mutableStateOf("Oleg")
    val hp = mutableStateOf(100)
    val gold = mutableStateOf(0)


    val networkLagMs = mutableStateOf(350)

    val log = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(ui: UiState, text: String){
    ui.log.value = (ui.log.value + text).takeLast(20)
}

fun main() = KoolApplication {
    val ui = UiState()

    val server = ServerWorld(initialPlayerId = ui.activePlayerId.value)
    val saver = SaveSystem()



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
        coroutineScope.launch {
            server.events.collect { event ->
                // collect - слушать поток (каждое событие будет попадать в данный слушатель)
                when (event) {
                    is DamageDealt -> pushLog(
                        ui,
                        "${event.playerId} нанёс ${event.amount} урона ${event.targetId}"
                    )

                    is QuestStateChanged -> pushLog(
                        ui,
                        "${event.playerId} перешёл на этап ${event.newState} квеста ${event.questId}"
                    )

                    is PlayerProgressSaved -> pushLog(
                        ui,
                        "Сохранён прогресс ${event.playerId} по причине ${event.reason}"
                    )
                }
            }
        }
    }
    // подписки на Flow надо запускать в корутинах
    // в Kool у сцены есть coroutineScope тут и запускаем
    addScene {
        setupUiScene(ClearColorLoad)
        addPanelSurface {
            Row{
             Button {
                    modifier
                        .margin(8.dp)
                        .onClick{
                            saver.save(server.playerState.value)
                            pushLog(ui, "Сохранено состояние для ${ui.activePlayerId.value}")
                        }
             }

             Button {
                modifier
                    .margin(8.dp)
                    .onClick {
                        val loaded = saver.load(ui.activePlayerId.value)
                        if (loaded != null) {
                            coroutineScope.launch {
                            server.applyLoaded(loaded)
                            pushLog(
                                ui,
                                "Загружено сохранение для ${loaded.playerId}: HP=${loaded.hp}, Gold=${loaded.gold}"
                            )}
                        } else {
                            pushLog(ui, "Нет сохранения для ${ui.activePlayerId.value}, создан новый персонаж")
                        }
                    }
            }
            }
        }
    }
}           // Подписка 1: слушаем события server.events

