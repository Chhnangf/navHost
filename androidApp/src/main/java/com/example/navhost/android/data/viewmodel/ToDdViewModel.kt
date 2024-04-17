package com.example.navhost.android.data.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.navhost.android.data.ToDoDao
import com.example.navhost.android.data.ToDoDatabase
import com.example.navhost.android.data.model.Status
import com.example.navhost.android.data.model.ToDoBox
import com.example.navhost.android.data.model.ToDoData
import com.example.navhost.android.data.repository.ToDoRepository
import com.example.navhost.android.worker.ReminderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

class ToDoViewModel(application: Application) : AndroidViewModel(application) {

    // 实例化ToDoDao、ToDoRepository
    private val toDoDao: ToDoDao
    private val repository: ToDoRepository

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> get() = _selectedDate


    init {

        val database = ToDoDatabase.getDatabase(application)
        toDoDao = database.toDoDao()
        // 创建并初始化ToDoRepository实例
        repository = ToDoRepository(toDoDao)

        // 初始化数据表数据
        viewModelScope.launch {
            fetchTodoBoxesWithTodosByModifiedDate(selectedDate.value)
        }
    }

    // 添加获取单个待办事项的方法
    suspend fun getTodoById(id: Long): Flow<ToDoData> {
        return repository.getTodoById(id)
    }

    fun deleteItem(toDoData: ToDoData) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteItem(toDoData)
            fetchTodoBoxesWithTodosByModifiedDate(selectedDate.value)
        }
    }


    fun insertOrUpdateData(toDoData: ToDoData) {
        viewModelScope.launch(Dispatchers.IO) {

            val existingTodo = toDoData.id?.let { repository.getTodoById(it) }
            if (existingTodo == null) {
                Log.d(
                    "ToDoViewModel",
                    "Inserting todo with ID: ${toDoData.id} ${toDoData.title} ${toDoData.description} ${toDoData.lastModifiedAt} ${toDoData.status} ${selectedDate.value}"
                )
                repository.insertData(toDoData)
            } else {
                Log.d(
                    "ToDoViewModel",
                    "Updating  todo with ID: ${toDoData.id} ${toDoData.title} ${toDoData.description} ${toDoData.lastModifiedAt} ${toDoData.status} ${selectedDate.value}"
                )
                repository.updateData(toDoData)
            }

            // 添加以下代码，判断是否存在提醒时间并安排通知
            val delay = calculateReminderDelay(toDoData)
            Log.d(
                "ToDoViewModel",
                "insertOrUpdateData -> reminderTime: $selectedDate.value with delay of $delay ms for todo: ${toDoData.title}"
            )

            if (toDoData.reminderTime != null) {
                if (delay > 0) {
                    val workRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)

                        .setInputData(
                            workDataOf(
                                "TODO_ID" to toDoData.id,
                                "TITLE" to toDoData.title,
                                // 不再传递提醒时间字符串，因为通知触发时不再需要解析时间
                            )
                        )
                        .build()

                    WorkManager.getInstance(getApplication()).enqueue(workRequest)
                }
            }
            viewModelScope.launch(Dispatchers.IO) {
                fetchTodoBoxesWithTodosByModifiedDate(selectedDate.value)
            }
        }
    }


    /**
     *  for checkbox
     */
    fun onTodoCheckedChange(todo: ToDoData, isChecked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updatedTodo = todo.copy(isChecked = isChecked)
            repository.updateData(updatedTodo)
            fetchTodoBoxesWithTodosByModifiedDate(selectedDate.value)
        }
    }


    // 2024-4-8-3：24
    // 插入新的收纳盒
    fun insertBox(box: ToDoBox) = viewModelScope.launch(Dispatchers.IO) {
        val newBoxId = repository.insertBox(box)
        if (newBoxId > 0) {
            Log.d("ToDoViewModel", "New box created with ID: $newBoxId")
        } else {
            Log.w("ToDoViewModel", "Failed to create new box or get its ID.")
        }
        // 更新StateFlow中的数据
        viewModelScope.launch(Dispatchers.Main) {
            fetchTodoBoxesWithTodosByModifiedDate(selectedDate.value)
        }
    }

    // 根据ID删除收纳盒
    fun deleteTodoBoxById(boxId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTodoBoxById(boxId)
            fetchTodoBoxesWithTodosByModifiedDate(selectedDate.value)
        }
    }

    fun updateTodoStatus(todoId: Long, newStatus: Status) {
        viewModelScope.launch {

            // 根据todoId获取该ToDoData数据
            val existingTodo = repository.getTodoById(todoId)
            // 订阅getTodoById返回的Flow，并使用transform修改数据
            repository.getTodoById(todoId).transform { todoData ->
                // 修改状态字段
                todoData.status = newStatus
                // 发送修改后的数据
                emit(todoData)
            }.firstOrNull()?.let { updatedTodo ->
                // 将修改后的数据保存回数据库
                repository.updateData(updatedTodo)
            }

        }
    }

    /**
     *  for search
     */
    // 搜索查询 StateFlow
    private val _searchQuery = MutableStateFlow("")

    // 用户输入内容的接口，将数据存储到_searchQuery中供后续读取
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    val searchQuery: StateFlow<String> get() = _searchQuery

    // 针对todoBoxesWithTodos进行搜索过滤
    // 2024-4-8-3：24
    // 搜索栏显示包含todo title的box
    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredBoxesWithTodos: StateFlow<List<Pair<ToDoBox, List<ToDoData>>>> =
        _searchQuery.flatMapLatest { query ->
            when {
                // 搜索栏为空时显示所有盒子及其内容
                query.isEmpty() -> todoBoxesWithTodosByDate
                // 搜索栏有内容时遍历titile是否匹配，返回其盒子及其内容
                else -> todoBoxesWithTodosByDate.map { boxes ->
                    boxes.map { (box, todos) ->
                        box to todos.filter { todo ->
                            todo.status != Status.DELETED && todo.title.contains(
                                query,
                                ignoreCase = true
                            )
                        }
                    }.filter { (_, filteredTodos) -> filteredTodos.isNotEmpty() }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /**
     *  4-15新增对数据库日期字段操作的api
     *  订阅_todoBoxesWithTodosByDate 观察ToDoBox、ToDoData数据流
     *  暴露todoBoxesWithTodosByDate 给ui层和数据层，提供获取value的方法
     */
    private val _todoBoxesWithTodosByDate =
        MutableStateFlow<List<Pair<ToDoBox, List<ToDoData>>>>(emptyList())
    val todoBoxesWithTodosByDate: StateFlow<List<Pair<ToDoBox, List<ToDoData>>>> get() = _todoBoxesWithTodosByDate


    // getTodoBoxesWithTodosByModifiedDate获取ToDoBox、ToDoData表内容
    // 更新_todoBoxesWithTodosByDate、_selectedDate
    private suspend fun fetchTodoBoxesWithTodosByModifiedDate(selectedDate: LocalDate) {

        val result = repository.getTodoBoxesWithTodosByModifiedDate(selectedDate)
        Log.d("ToDoViewModel", "fetchTodoBoxesWithTodosByModifiedDate result: $result")
        _todoBoxesWithTodosByDate.emit(result)
        _selectedDate.value = selectedDate

    }

    /**
     *  1. ToDoScreen insertBox时调用此方法
     *  2. ToDoScreen insertTodo时调用此方法
     *  3. ToDoAddScreen updateTodo
     *  4. ToDoAddScreen deleted 待实装
     *  5.
     */
    fun fetchTodoBoxesBySelectedDate(selected: LocalDate) = viewModelScope.launch(Dispatchers.IO) {
        val result = repository.getTodoBoxesWithTodosByModifiedDate(selected)
        _todoBoxesWithTodosByDate.emit(result)
        fetchTodoBoxesWithTodosByModifiedDate(selected)
    }

    // 4-20 subCheckbox
    fun onSubTaskCheckedChange(todo: ToDoData, index: Int, newChecked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(
                "ToDoViewModel",
                "todo: $todo/nindex:$index newChecked $newChecked"
            )
            if (todo.subTasks.isNotEmpty() && index >= 0 && index < todo.subTasks.size) {
                val subTasksCopy = todo.subTasks.toMutableList()
                subTasksCopy[index].isChecked = newChecked

                val updatedTodo = todo.copy(
                    subTasks = subTasksCopy,
                    subTasksDoneCount = subTasksCopy.count { it.isChecked }
                )

                // 判断是否所有子任务都已完成，如果是，则更新母任务状态为已完成
                val allSubTasksDoneCount = updatedTodo.subTasksDoneCount == updatedTodo.subTaskCount
                if (allSubTasksDoneCount) {
                    updatedTodo.status = Status.COMPLETED
                } else {
                    // 如果不是所有子任务都完成，根据是否有未完成的子任务来决定母任务状态
                    updatedTodo.status =
                        if (updatedTodo.subTasksDoneCount > 0) Status.IN_PROGRESS else Status.PENDING
                }
                Log.d(
                    "ToDoViewModel",
                    "todo.subTaskCount & subTasksDoneCount: ${updatedTodo.subTaskCount}  ${updatedTodo.subTasksDoneCount} \ntodo.status & isChecked: ${updatedTodo.status} ${subTasksCopy[index].isChecked}"
                )
                repository.updateData(updatedTodo)
                fetchTodoBoxesWithTodosByModifiedDate(selectedDate.value)
            } else {
                // 处理无效索引情况，例如打印日志或忽略此次操作
                Log.e("ToDoViewModel", "Invalid subTask index $index for todo with id ${todo.id}")
            }
        }
    }
}

    fun calculateReminderDelay(toDoData: ToDoData): Long {

        val currentDate = LocalDate.now(ZoneId.systemDefault())
        // 检查reminderTime是否为null，并提供一个默认值（例如当前时间）
        val reminderTime = toDoData.reminderTime ?: LocalTime.now(ZoneId.systemDefault())


        val reminderDateTime = LocalDateTime.of(currentDate, reminderTime)
        // 将提醒时间转换为Instant对象
        val reminderInstant = reminderDateTime.atZone(ZoneId.systemDefault()).toInstant()

        val currentTime = Instant.now()
        // 计算延迟时间（以毫秒为单位）
        val delay = ChronoUnit.MILLIS.between(currentTime, reminderInstant)

        return delay
    }