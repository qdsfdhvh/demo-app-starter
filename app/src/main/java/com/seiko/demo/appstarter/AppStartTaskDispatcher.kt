package com.seiko.demo.appstarter

import android.os.Process
import android.os.Process.THREAD_PRIORITY_FOREGROUND
import android.os.Process.THREAD_PRIORITY_LOWEST
import android.util.Log
import androidx.annotation.IntRange
import androidx.annotation.MainThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.collections.set
import kotlin.reflect.KClass

private const val WAITING_TIME = 10000L

private typealias TaskKey = KClass<out TaskInterface>

class AppStartTaskDispatcher private constructor(
  private val startTaskList: List<AppStartTask>,
  private val allTaskWaitTimeOut: Long,
  private val isShowLog: Boolean,
  needWaitCount: Int
) {

  class Builder {
    private val startTaskList = ArrayList<AppStartTask>()
    private var allTaskWaitTimeOut = WAITING_TIME
    private var isShowLog = false
    private var needWaitCount = 0

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
      if (task.ifNeedWait) {
        needWaitCount++
      }
    }

    fun addAppStartTasks(tasks: Collection<TaskInterface>) = apply {
      tasks.forEach(::addAppStartTask)
    }

    fun build() = AppStartTaskDispatcher(
      startTaskList = startTaskList,
      allTaskWaitTimeOut = allTaskWaitTimeOut,
      isShowLog = isShowLog,
      needWaitCount = needWaitCount
    )
  }

  private val taskMap = HashMap<TaskKey, AppStartTask>()
  private val taskChildListMap = HashMap<TaskKey, ArrayList<TaskKey>>()

  private val countDownLatch = CountDownLatch(needWaitCount)

  @MainThread
  fun start() {
    val startTime = System.currentTimeMillis()

    // 启动Task
    dispatchAppStartTask(getSortResult())

    // 阻塞等待
    try {
      countDownLatch.await(allTaskWaitTimeOut, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
      e.printStackTrace()
    }

    log("Finish all await Tasks, costTime: ${System.currentTimeMillis() - startTime}ms")
  }

  /**
   * 启动所有Task任务
   */
  private fun dispatchAppStartTask(sortTaskList: List<AppStartTask>) {
    val mainThreadTasks = ArrayList<AppStartTask>(sortTaskList.size)
    sortTaskList.forEach { task ->
      if (task.isRunOnMainThread()) {
        mainThreadTasks.add(task)
      } else {
        // 启动子线程的任务
        task.runOnExecutor().execute(task.toProxy())
      }
    }
    // 启动主线程的任务
    mainThreadTasks.forEach { task ->
      task.toProxy().run()
    }
  }

  /**
   * 拓扑排序
   * 重新排列Task任务
   */
  private fun getSortResult(): List<AppStartTask> {
    val deque = ArrayDeque<TaskKey>()

    // 先循环每个Task，确定深度、放入Map、创建childList
    val taskDepthMap = HashMap<TaskKey, Int>()
    for (task in startTaskList) {
      if (taskDepthMap.containsKey(task.taskKey)) {
        throw RuntimeException("任务重复了: " + task.taskKey)
      }

      val dependsSize = task.dependsSize
      if (dependsSize == 0) {
        // 深度为0的Task注入当如队列
        deque.addLast(task.taskKey)
      }

      taskDepthMap[task.taskKey] = dependsSize
      taskMap[task.taskKey] = task
      taskChildListMap[task.taskKey] = ArrayList()
    }

    // 再次循环每个Task，并放入其需要依赖的Task的childList中
    for (childTask in startTaskList) {
      if (childTask.getDependsTaskList().isEmpty()) continue
      childTask.getDependsTaskList().forEach { taskKey ->
        taskChildListMap[taskKey]!!.add(childTask.taskKey)
      }
    }

    // 根据深度，逐个添加进sortTaskList
    val sortTaskList = ArrayList<AppStartTask>(startTaskList.size)
    while (!deque.isEmpty()) {
      val taskKey = deque.removeFirst()
      sortTaskList.add(taskMap[taskKey]!!)

      val childTaskList = taskChildListMap[taskKey]
      if (childTaskList.isNullOrEmpty()) continue

      // 添加完成后对其每个子Task的深度-1
      for (childTaskKey in childTaskList) {
        val depth = taskDepthMap[childTaskKey]!! - 1
        taskDepthMap[childTaskKey] = depth
        if (depth == 0) {
          deque.addLast(childTaskKey)
        }
      }
    }

    // 排序后的TaskList数量必须相同
    if (sortTaskList.size != startTaskList.size) {
      throw RuntimeException("出现坏环")
    }

    logSortTask(sortTaskList)
    return sortTaskList
  }

  private fun finishTask(task: AppStartTask) {
    // notify children
    taskChildListMap[task.taskKey]!!.forEach { childTaskKey ->
      taskMap[childTaskKey]!!.notifyNow()
    }
    // needWait -1
    if (task.ifNeedWait) {
      countDownLatch.countDown()
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
      dispatcher.finishTask(appStartTask)
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

  val ifNeedWait: Boolean get() = !isRunOnMainThread() && isNeedWait()

  val dependsSize: Int get() = getDependsTaskList().size
}

private class AppStartTask(task: TaskInterface) : TaskInterface by task {

  val taskKey: TaskKey = task::class

  private val depends by lazy { CountDownLatch(task.dependsSize) }

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