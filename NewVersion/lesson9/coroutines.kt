package lesson9

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

import kotlinx.coroutines.launch  // запускает корутину
import kotlinx.coroutines.Job     // контроллер запущенной корутины
import kotlinx.coroutines.isActive // проверка жива ли ещё корутина - полезно для циклов
import kotlinx.coroutines.delay

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
import kotlinx.serialization.builtins.ShortArraySerializer
import kotlinx.serialization.encodeToString //запись в файл
import kotlinx.serialization.decodeFromString // Чтение файла
import kotlinx.serialization.json.Json // Формат файла Json


import java.io.File

//В любой игре есть много процессов, опирающихся на время
// Например: яд тикает 1 раз в секунду
//кулдаун удара 1.5 секунды
// Задержка сети 300мс
// Квест с событием открывает дверь через 5 секунд и т.д

// Если все эти процессы делать через onUpdate и таймер вручную - это быстро превращается в кашу

// Корутины решают эту проблему:
// 1. Позволяют писать время как обычный код: например подождал  -> сделал действие -> подождал ->  действие
// 2. В процессе выполнения не замораживает игру и UI
// 3. Удобно отменяются (например яд перезапускется, если наложить  новый, а старый яд отменяем)

// Корутина - легковесная задача, которая может выполняться, параллельно другим задачам и основному потоку

// Основные команды корутин:
// launch{...} - запускают корутину (включить поток)
// delay(ms) - заставляет корутину ждать ограниченное число времени, но не замораживает саму игру
// Job + cancel()
// Job - контроллер управления корутиной
// cancel() - остановить выполнение корутины (например снять эффект яда)

// функция delay не будет работать за пределами корутины launch
// потому что delay - это suspend функция

// suspend fun - функция, которая может приостанавливаться (ждать) - обычные функции так не умеют
// suspend функцию можно вызвать только внутри запущенной корутины или внутри такой же suspend функции

//scene.coroutineScope - это свой корутинный скуп Kool внутри сцены, для чего?
//Когда сцена будет закрываться - корутины внутри этой сцены автоматически прекратятся
//Это просто безопаснее, чем глобальные корутины, про которые мы можем забыть или не сохранить

class GameState{
    val playerId = mutableStateOf("Oleg")

    val hp = mutableStateOf(100)
    val maxHp = 100

    val poisonTicksLeft = mutableStateOf(0)
    val regenTicksLeft = mutableStateOf(0)

    val attackCoolDownMsLeft = mutableStateOf(0L)

    val logLines = mutableStateOf<List<String>>(emptyList())
}

fun pushLog(game: GameState, text: String){
    game.logLines.value = (game.logLines.value + text).takeLast(20)
}

// EffectManager - система для эффектов по времени

class EffectManager (
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope
    // scope - место где и будут запускаться и жить корутины
    // передаём сюда scene.coroutineScope чтобы всё было привязано к сцене
){
    private var poisonJob: Job? = null
    // Job - эо задача-корутина

    private var regenJob: Job? = null

    fun applyPoison(ticks: Int, damagePerTick: Int, intervalMs: Long){
        poisonJob?.cancel()
        //если яд, уже был применён, аннулируем его (отменяем)
        // ?. - безопасный вызов, значит, если poisonJob == null, то cancel не вызовется

        //Обновляем состояние игрового чиста тиков яда (добавляем к счетчику)
        game.poisonTicksLeft.value += ticks

        poisonJob = scope.launch {
            //запускаем корутины нанесения урона от яда

            // создать цикл while который проверит активна ли корутина, и что счётчик тиков больше 0
            // внутри цикла задержка
            // отнятие 1 тика от состояния тиков яда
            //и отнятия здоровья, с условием, что оно не упадёт ниже 0
            // Публикуем лог о нанесения урона от яда
            while(isActive && game.poisonTicksLeft.value > 0){

                delay (intervalMs)
                game.poisonTicksLeft.value -= 1

                game.hp.value = (game.hp.value - damagePerTick).coerceAtLeast(0)

                pushLog(game, "Тик яда: -$damagePerTick, HP: ${game.hp.value} / ${game.maxHp}")
            }
            pushLog(game, "Эффект яда завершён")
        }
    }
    fun applyRegen(ticks: Int, healPerTick: Int, intervalMs: Long){
        regenJob?.cancel()

        game.regenTicksLeft.value += ticks
        pushLog(game, "эффект регена примерен на ${game.playerId} длительность ${intervalMs}")

        regenJob = scope.launch {
            while (isActive && game.regenTicksLeft.value > 0){
                delay(intervalMs)

                game.regenTicksLeft.value -= 1
                game.hp.value = (game.hp.value + healPerTick).coerceAtLeast(game.maxHp)
                pushLog(game, "Тик регена: + $healPerTick, HP: ${game.hp.value} / ${game.maxHp}")
            }
            pushLog(game, "Эффект регена завершён")
        }
    }

    fun cancelPoison(){
        poisonJob?.cancel()
        poisonJob = null
        game.poisonTicksLeft.value = 0
        pushLog(game, "Яд снят (cancel)")
    }

    fun cancelRegen(){
        regenJob?.cancel()
        regenJob = null
        game.regenTicksLeft.value = 0
        pushLog(game, "Реген снят (cancel)")
    }
}


class CooldownManager(
    private val game: GameState,
    private val scope: kotlinx.coroutines.CoroutineScope
){
    private  var cooldownJob: Job? = null

    fun startAttackCooldown(totalMs: Long) {
        cooldownJob?.cancel()

        game.attackCoolDownMsLeft.value = totalMs
        pushLog(game, "Кулдаун атаки ${totalMs}мс")

        cooldownJob = scope.launch {
            val step = 100L

            while(isActive && game.attackCoolDownMsLeft.value > 0L){
                delay(step)
                game.attackCoolDownMsLeft.value = (game.attackCoolDownMsLeft.value - step).coerceAtLeast(0)
            }
        }
    }
    fun canAttack(): Boolean{
        return game.attackCoolDownMsLeft.value <= 0L
    }
}

fun main() = KoolApplication {
    val game = GameState()

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
        val effects = EffectManager(game, coroutineScope)
        val cooldowns = CooldownManager(game, coroutineScope)

        SharedActions.effects = effects
        SharedActions.cooldown = cooldowns
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
                Text("HP: ${game.hp.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Тики яда: ${game.regenTicksLeft.use()}") {
                    modifier.margin(bottom = sizes.gap)
                }
                Text("Тики реген: ${game.regenTicksLeft.use()}"){
                    modifier.margin(bottom = sizes.gap)
                }

                Row{
                    modifier.margin(top = sizes.smallGap)
                    Button("Яд +5") {
                        modifier.margin(end=8.dp).onClick {
                            SharedActions.effects?.applyPoison(5,2,1000L)
                        }
                    }
                    Button("Отменить яд") {
                        modifier.margin(end=8.dp).onClick {

                        }
                    }
                }
                Row{
                    modifier.margin(top = sizes.smallGap)
                    Button("Реген +5") {
                        modifier.margin(end=8.dp).onClick {
                            SharedActions.effects?.applyRegen(5,2,1000L)
                        }
                    }
                    Button("Отменить реген") {
                        modifier.margin(end=8.dp).onClick {
                            SharedActions.effects?.cancelRegen()
                        }
                    }
                }
                Row{
                    modifier.margin(top = sizes.smallGap)
                    Button("Атаковать (кулдаун 1200мс)") {
                        modifier.margin(end = 8.dp).onClick{
                            val cd = SharedActions.cooldown

                            if (cd == null){
                                pushLog(game, "CooldownManager ещё не готов")
                                return@onClick
                            }

                            if (!cd.canAttack()){
                                pushLog(game, "Атаковать нельзя: кулдаун ещё идёт")
                                return@onClick
                            }
                            cd.startAttackCooldown(totalMs = 1200L)
                        }
                    }
                }
                Text("Логи:") { modifier.margin(top = sizes.gap) }

                val lines = game.logLines.use()
                for (line in lines){
                    Text(line){modifier.font(sizes.smallText)}
                }
            }
        }
    }
}


//------ Shared Actions -  мост между сценами ------//

object SharedActions{
    var effects: EffectManager? = null
    var cooldown: CooldownManager? = null
}