# demo-app-starter

基于[AppStartFaster](https://github.com/NoEndToLF/AppStartFaster)整理的单文件启动器；

关于此类的项目有很多，但是都比较庞大，遂删掉了部分功能整理了一个单文件的，便于使用。

## 使用

直接复制[AppStartTaskDispatcher](app/src/main/java/com/seiko/demo/appstarter/AppStartTaskDispatcher.kt)；

```kotlin
AppStartTaskDispatcher.Builder()
      .setShowLog(true)
      .setAllTaskWaitTimeOut(5000)
      .addAppStartTask(TASK1)
      .addAppStartTask(TASK2)
      .addAppStartTasks(TASK_LIST)
      .build()
      .start()
```
