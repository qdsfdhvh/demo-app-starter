package com.seiko.demo.appstarter

import android.os.SystemClock
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.reflect.KClass

val START_TASKS: List<TaskInterface> = listOf(
  LoggerStartTask(),
  SpStartTask(),
  OtherMainStartTask(),

  NetworkStartTask(),
  OtherIOStartTask(),

  OtherIOMainWaitStartTask()
)

/**
 * 主线程运行 第一个运行的
 */
private class LoggerStartTask : BaseStartTask() {
  override fun run() {}

  override fun getDependsTaskList(): List<KClass<out TaskInterface>> {
    return emptyList()
  }
}

/**
 * 主线程运行
 */
private class SpStartTask : BaseStartTask() {
  override fun run() {
    SystemClock.sleep(30)
  }

  override fun isRunOnMainThread() = false
}

/**
 * 主线程运行
 */
private class OtherMainStartTask : BaseStartTask() {

  override fun run() {
    SystemClock.sleep(100)
  }

  override fun getDependsTaskList(): List<KClass<out TaskInterface>> {
    return super.getDependsTaskList() + listOf(
      SpStartTask::class
    )
  }
}

/**
 * IO线程运行
 */
private class NetworkStartTask : BaseStartTask() {
  override fun run() {
    SystemClock.sleep(500)
  }

  override fun isRunOnMainThread() = false

  override fun getDependsTaskList(): List<KClass<out TaskInterface>> {
    return super.getDependsTaskList() + listOf(
      SpStartTask::class
    )
  }
}

/**
 * IO线程运行
 */
private class OtherIOStartTask : BaseStartTask() {
  override fun run() {
    SystemClock.sleep(800)
  }

  override fun isRunOnMainThread() = false
}

/**
 * IO线程运行 但是需其等待完成的
 */
private class OtherIOMainWaitStartTask : BaseStartTask() {
  override fun run() {
    SystemClock.sleep(100)
  }

  override fun isRunOnMainThread() = false

  override fun isNeedWait() = true
}

/**
 * 配置默认的IO线程池 与 全员依赖的首个StartTask
 */
private abstract class BaseStartTask : TaskInterface {

  override fun getDependsTaskList(): List<KClass<out TaskInterface>> {
    return listOf(LoggerStartTask::class)
  }

  override fun runOnExecutor(): Executor = executor

  companion object {
    val executor: ExecutorService = Executors.newCachedThreadPool()
  }
}