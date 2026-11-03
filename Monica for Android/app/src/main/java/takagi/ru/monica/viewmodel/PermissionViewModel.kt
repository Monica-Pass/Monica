package takagi.ru.monica.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import takagi.ru.monica.data.model.PermissionCategory
import takagi.ru.monica.data.model.PermissionInfo
import takagi.ru.monica.data.model.PermissionStats
import takagi.ru.monica.data.model.PermissionStatus
import takagi.ru.monica.repository.PermissionRepository

/**
 * 权限管理ViewModel
 * Permission management ViewModel
 */
class PermissionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PermissionRepository(application)

    private val _permissionsByCategory = MutableStateFlow<Map<PermissionCategory, List<PermissionInfo>>>(emptyMap())
    val permissionsByCategory: StateFlow<Map<PermissionCategory, List<PermissionInfo>>> =
        _permissionsByCategory.asStateFlow()

    private val _permissionStats = MutableStateFlow<PermissionStats?>(null)
    val permissionStats: StateFlow<PermissionStats?> = _permissionStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadPermissions()
    }

    /**
     * 加载权限信息
     * Load permission information
     */
    fun loadPermissions() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                publish(repository.getAllPermissions())
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * 刷新权限状态
     * Refresh permission status
     */
    fun refreshPermissions() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 强制刷新，不使用缓存
                publish(repository.getAllPermissions(forceRefresh = true))
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun publish(items: List<PermissionInfo>) {
        _permissionsByCategory.value = items.groupBy { it.category }
        _permissionStats.value = PermissionStats(0, 0, items.size,
            items.count { it.status == PermissionStatus.GRANTED }, items.count { it.requested })
    }
}
