package com.seiko.demo.appstarter

import android.os.Process
import android.os.Process.THREAD_PRIORITY_FOREGROUND
import android.os.Process.THREAD_PRIORITY_LOWEST
import android.util.Log
import androidx.annotation.IntRange
import androidx.annotation.MainThread
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.set
import kotlin.reflect.KClass

private const val WAITING_TIME = 10000L

private typealias TaskKey = KClass<out TaskInterface>

class AppStartTaskDispatcher {

  private val startTaskList = ArrayList<AppStartTask>()
  private val taskHashMap = HashMap<TaskKey, AppStartTask>()
  private val taskChildHashMap = HashMap<TaskKey, MutableList<TaskKey>>()

  private lateinit var countDownLatch: CountDownLatch

  private val needWaitCount = AtomicInteger()
  private var allTaskWaitTimeOut = WAITING_TIME

  private var isShowLog = false
  private var startTime = 0L

  fun setShowLog(showLog: Boolean) = apply {
    isShowLog = showLog
  }

  fun setAllTaskWaitTimeOut(
    @IntRange(from = 50, to = Long.MAX_VALUE) timeOut: Long
  ) = apply {
    allTaskWaitTimeOut = timeOut
  }

  fun addAppStartTask(task: TaskInterface) = apply {
    startTaskList.add(AppStartTask(task))
    if (task.ifNeedWait()) {
      needWaitCount.getAndIncrement()
    }
  }

  fun addAppStartTasks(tasks: Collection<TaskInterface>) = apply {
    tasks.forEach(::addAppStartTask)
  }

  @MainThread
  fun start() = apply {
    startTime = System.currentTimeMillis()
    countDownLatch = CountDownLatch(needWaitCount.get())
    dispatchAppStartTask(getSortResult())
  }

  fun await() {
    check(this::countDownLatch.isInitialized) { "must run start() before await()" }

    // 阻塞等待
    try {
      countDownLatch.await(allTaskWaitTimeOut, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
      e.printStackTrace()
    }

    log("Finish all await Tasks, costTime: ${System.currentTimeMillis() - startTime}ms")
  }

  // 分别处理主线程和子线程的任务
  private fun dispatchAppStartTask(sortTaskList: List<AppStartTask>) {
    val mainThreadTasks = ArrayList<AppStartTask>(sortTaskList.size)
    sortTaskList.forEach { task ->
      if (task.isRunOnMainThread()) {
        mainThreadTasks.add(task)
      } else {
        // 发送子线程的任务
        task.runOnExecutor().execute(task.toProxy())
      }
    }
    // 发送主线程的任务
    mainThreadTasks.forEach { task ->
      task.toProxy().run()
    }
  }

  /**
   * 拓扑排序
   * taskIntegerHashMap每个Task的入度
   * taskHashMap每个Task
   * taskChildHashMap每个Task的孩子
   * deque 入度为0的Task
   */
  private fun getSortResult(): List<AppStartTask> {
    val deque = ArrayDeque<TaskKey>()

    val taskDepthHashMap = HashMap<TaskKey, Int>()
    for (task in startTaskList) {
      if (!taskDepthHashMap.containsKey(task.taskKey)) {
        taskHashMap[task.taskKey] = task
        taskDepthHashMap[task.taskKey] = task.getDependsTaskList().size
        taskChildHashMap[task.taskKey] = ArrayList()
        // 添加深度为0的Task
        if (taskDepthHashMap[task.taskKey] == 0) {
          deque.offer(task.taskKey)
        }
      } else {
        throw RuntimeException("任务重复了: " + task.taskKey)
      }
    }

    // 给Task添加把子Task的key
    for (task in startTaskList) {
      if (task.getDependsTaskList().isNotEmpty()) {
        task.getDependsTaskList().forEach { dependsKClass ->
          taskChildHashMap[dependsKClass]?.add(task.taskKey)
        }
      }
    }

    // 按深度逐个添加进排序后的TaskList
    val sortTaskList = ArrayList<AppStartTask>(startTaskList.size)
    while (!deque.isEmpty()) {
      val dependsKClass = deque.poll()!!
      sortTaskList.add(taskHashMap[dependsKClass]!!)
      for (classChild in taskChildHashMap[dependsKClass]!!) {
        taskDepthHashMap[classChild] = taskDepthHashMap[classChild]!! - 1
        if (taskDepthHashMap[classChild]!! == 0) {
          deque.offer(classChild)
        }
      }
    }

    // 排序后的TaskList数量必须
    if (sortTaskList.size != startTaskList.size) {
      throw RuntimeException("出现坏环")
    }

    logSortTask(sortTaskList)
    return sortTaskList
  }

  private fun finishAppStartTask(task: AppStartTask) {
    // notify children
    taskChildHashMap[task.taskKey]?.forEach { childTask ->
      taskHashMap[childTask]?.notifyNow()
    }
    // needWait -1
    if (task.ifNeedWait()) {
      countDownLatch.countDown()
      needWaitCount.getAndDecrement()
    }
  }

  private fun AppStartTask.toProxy() = AppStartTaskProxy(
    appStartTask = this,
    dispatcher = this@AppStartTaskDispatcher
  )

  private class AppStartTaskProxy(
    private val appStartTask: AppStartTask,
    private val dispatcher: AppStartTaskDispatcher
  ) : Runnable {

    override fun run() {
      Process.setThreadPriority(appStartTask.priority())
      // 尝试等待前置的Task完成
      appStartTask.waitToNotify()

      var costTime = System.currentTimeMillis()
      appStartTask.run()
      dispatcher.finishAppStartTask(appStartTask)
      costTime = System.currentTimeMillis() - costTime

      dispatcher.log("Finish Task[${appStartTask.taskKey.simpleName}], costTime: ${costTime}ms")
    }
  }

  private fun logSortTask(sortTaskList: List<AppStartTask>) {
    if (isShowLog) {
      log(sortTaskList.joinToString(
        prefix = "Task Sort ",
        separator = "-->"
      ) { it.taskKey.simpleName!! })
    }
  }

  private fun log(msg: String) {
    if (isShowLog) {
      Log.i("AppStartTask", msg)
    }
  }
}

interface TaskInterface : Runnable {

  @IntRange(from = THREAD_PRIORITY_FOREGROUND.toLong(), to = THREAD_PRIORITY_LOWEST.toLong())
  fun priority(): Int = Process.THREAD_PRIORITY_BACKGROUND

  fun getDependsTaskList(): List<KClass<out TaskInterface>> = emptyList() // List<TaskKey>

  fun isRunOnMainThread(): Boolean = true

  fun isNeedWait(): Boolean = false

  fun runOnExecutor(): Executor

  fun ifNeedWait(): Boolean {
    return !isRunOnMainThread() && isNeedWait()
  }
}

private class AppStartTask(task: TaskInterface) : TaskInterface by task {

  val taskKey: TaskKey = task::class

  private val depends by lazy {
    CountDownLatch(task.getDependsTaskList().size)
  }

  fun waitToNotify() {
    try {
      depends.await()
    } catch (e: InterruptedException) {
      e.printStackTrace()
    }
  }

  fun notifyNow() {
    depends.countDown()
  }
}