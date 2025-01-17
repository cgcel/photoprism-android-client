package ua.com.radiokot.photoprism.features.gallery.view.model

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import ua.com.radiokot.photoprism.extension.autoDispose
import ua.com.radiokot.photoprism.extension.kLogger
import ua.com.radiokot.photoprism.extension.toMainThreadObservable
import ua.com.radiokot.photoprism.features.gallery.data.model.Person
import ua.com.radiokot.photoprism.features.gallery.data.storage.PeopleRepository
import ua.com.radiokot.photoprism.features.gallery.data.storage.SearchPreferences

class GallerySearchPeopleViewModel(
    private val peopleRepository: PeopleRepository,
    searchPreferences: SearchPreferences,
) : ViewModel() {
    private val log = kLogger("GallerySearchPeopleVM")
    private val stateSubject = BehaviorSubject.createDefault<State>(State.Loading)
    val state = stateSubject.toMainThreadObservable()
    val isViewVisible = searchPreferences.showPeople.toMainThreadObservable()

    /**
     * Non-null set of the selected person IDs, **empty** if nothing is selected.
     */
    val selectedPersonIds = MutableLiveData<Set<String>>(emptySet())

    init {
        subscribeToRepository()
        subscribeToPeopleSelection()
    }

    fun updateIfNotFresh() {
        log.debug {
            "updateIfNotFresh(): begin_loading"
        }

        peopleRepository.updateIfNotFresh()
    }

    private fun subscribeToRepository() {
        peopleRepository.items
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { people ->
                if (people.isEmpty() && peopleRepository.isNeverUpdated) {
                    stateSubject.onNext(State.Loading)
                } else {
                    postReadyState()
                }
            }
            .autoDispose(this)

        peopleRepository.errors
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { error ->
                log.error(error) {
                    "subscribeToRepository(): people_loading_failed"
                }

                stateSubject.onNext(State.LoadingFailed)
            }
            .autoDispose(this)
    }

    private fun postReadyState() {
        val repositoryPeople = peopleRepository.itemsList

        val selectedPersonIds = selectedPersonIds.value!!

        log.debug {
            "postReadyState(): posting_ready_state:" +
                    "\npeopleCount=${repositoryPeople.size}," +
                    "\nselectedPeopleCount=${selectedPersonIds.size}"
        }

        val hasAnyNames = repositoryPeople.any(Person::hasName)

        stateSubject.onNext(
            State.Ready(
                people = repositoryPeople.map { person ->
                    PersonListItem(
                        source = person,
                        isPersonSelected = person.id in selectedPersonIds,
                        isNameShown = hasAnyNames,
                    )
                }
            )
        )
    }

    private fun subscribeToPeopleSelection() {
        selectedPersonIds.observeForever {
            val currentState = stateSubject.value
            if (currentState is State.Ready) {
                postReadyState()
            }
        }
    }

    fun onPersonItemClicked(item: PersonListItem) {
        val currentState = stateSubject.value
        check(currentState is State.Ready) {
            "People are clickable only in the ready state"
        }

        log.debug {
            "onPersonItemClicked(): person_item_clicked:" +
                    "\nitem=$item"
        }

        if (item.source != null) {
            val id = item.source.id
            val currentlySelectedPersonIds = selectedPersonIds.value!!

            if (currentlySelectedPersonIds.contains(id)) {
                log.debug {
                    "onPersonItemClicked(): unselect:" +
                            "\npersonId=$id"
                }
                selectedPersonIds.value = currentlySelectedPersonIds - id
            } else {
                log.debug {
                    "onPersonItemClicked(): select:" +
                            "\npersonId=$id"
                }
                selectedPersonIds.value = currentlySelectedPersonIds + id
            }
        }
    }

    fun onReloadPeopleClicked() {
        log.debug {
            "onReloadPeopleClicked(): reload_people_clicked"
        }

        updateIfNotFresh()
    }

    fun getPersonThumbnail(uid: String): String? =
        peopleRepository.getLoadedPerson(uid)?.smallThumbnailUrl

    sealed interface State {
        object Loading : State
        class Ready(
            val people: List<PersonListItem>,
        ) : State

        object LoadingFailed : State
    }
}
