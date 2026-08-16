package garden.appl.mitch.database.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.viewModelScope
import garden.appl.mitch.database.AppDatabase
import garden.appl.mitch.database.installation.GameInstallation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal open class GameViewModel(app: Application, type: GameRepository.Type) : AndroidViewModel(app) {
    // Populated asynchronously in init: building the Room database and running the first query
    // can take a while on a cold start, and doing it with runBlocking on the main thread froze
    // the Library tab until it finished. Starts empty; the first emission arrives off the main
    // thread once the database is ready (AppDatabase.getDatabase pre-warms it at app startup).
    val games: MediatorLiveData<List<GameInstallation>> = MediatorLiveData()

    init {
        viewModelScope.launch {
            val gameDao = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(getApplication()).gameDao
            }
            val repository = GameRepository(gameDao, type)
            games.addSource(repository.games) { games.value = it }
        }
    }

    class Pending(app: Application) : GameViewModel(app, GameRepository.Type.Pending)
    class Installed(app: Application) : GameViewModel(app, GameRepository.Type.Installed)
    class Downloads(app: Application) : GameViewModel(app, GameRepository.Type.Downloads)
    class WebCached(app: Application) : GameViewModel(app, GameRepository.Type.WebCached)
}
