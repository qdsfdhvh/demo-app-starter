package com.seiko.demo.appstarter

import android.app.Application

class DemoApp : Application() {
  override fun onCreate() {
    super.onCreate()
    AppStartTaskDispatcher.Builder()
      .setShowLog(true)
      .setAllTaskWaitTimeOut(5000)
      .addAppStartTasks(START_TASKS)
      .build()
      .start()
  }
}