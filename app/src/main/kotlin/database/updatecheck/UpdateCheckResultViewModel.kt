package garden.appl.mitch.database.updatecheck

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.viewModelScope
import garden.appl.mitch.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateCheckResultViewModel(app: Application) : AndroidViewModel(app) {
    // Populated asynchronously in init; see GameViewModel for why the database access is
    // off the main thread instead of a blocking runBlocking in the constructor.
    val availableUpdates: MediatorLiveData<List<InstallUpdateCheckResult>> = MediatorLiveData()

    init {
        viewModelScope.launch {
            val repository = withContext(Dispatchers.IO) {
                UpdateCheckResultRepository(AppDatabase.getDatabase(getApplication()).updateCheckDao)
            }
            availableUpdates.addSource(repository.availableUpdates) { availableUpdates.value = it }
        }
    }
}
