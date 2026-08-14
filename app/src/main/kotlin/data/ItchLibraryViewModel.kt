package garden.appl.mitch.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
import garden.appl.mitch.client.ItchLibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ItchLibraryViewModel(private val repository: ItchLibraryRepository) : ViewModel() {
    
    private var cachedItemsFlow: Flow<PagingData<ItchLibraryItem>>? = null
    private var cachedAndroidOnly: Boolean? = null

    fun getOwnedItems(searchString: String, androidOnly: Boolean) : Flow<PagingData<ItchLibraryUiModel>> {
        // The "Only Android" toggle must re-create the paging source so the request
        // goes out with itch.io's `platform=android` parameter (client-side filtering
        // can no longer detect Android games reliably).
        if (cachedItemsFlow == null || cachedAndroidOnly != androidOnly) {
            cachedAndroidOnly = androidOnly
            cachedItemsFlow = repository.getLibraryStream(androidOnly).cachedIn(viewModelScope)
        }
        val itemsFlow = cachedItemsFlow!!

        return itemsFlow
            .map { pagingData ->
                pagingData.filter { item -> item.title.contains(searchString, ignoreCase = true) }
            }
            .map { pagingData -> pagingData.map { item -> ItchLibraryUiModel.Item(item) } }
            .map { 
                it.insertSeparators { before, after ->
                    if (after == null)
                        return@insertSeparators null

                    if (before == null) {
                        return@insertSeparators ItchLibraryUiModel.Separator(
                            after.item.purchaseDate, 
                            isFirst = true
                        )
                    }

                    if (before.item.purchaseDate != after.item.purchaseDate) {
                        return@insertSeparators ItchLibraryUiModel.Separator(
                            after.item.purchaseDate,
                            isFirst = false
                        )
                    }

                    null
                }
            }
    }
}