package eu.kanade.tachiyomi.ui.manga.track

import android.os.Bundle
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.mdlist.AnimePlanet
import eu.kanade.tachiyomi.data.track.mdlist.MangaUpdates
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.utils.FollowStatus
import eu.kanade.tachiyomi.source.online.utils.MdUtil
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import kotlinx.coroutines.*
import rx.Subscription
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get


class TrackPresenter(
        val manga: Manga,
        preferences: PreferencesHelper = Injekt.get(),
        private val db: DatabaseHelper = Injekt.get(),
        private val trackManager: TrackManager = Injekt.get()
) : BasePresenter<TrackController>() {

    private var trackList: List<TrackItem> = emptyList()

    private lateinit var validServices: List<TrackService>

    private val mdex by lazy { Injekt.get<SourceManager>().getMangadex() as HttpSource }

    private var searchSubscription: Subscription? = null

    private var refreshExceptionHandler = CoroutineExceptionHandler { _, error -> GlobalScope.launch(Dispatchers.Main) { view?.onRefreshError(error) } }

    private var searchExceptionHandler = CoroutineExceptionHandler { _, error -> GlobalScope.launch(Dispatchers.Main) { view?.onSearchResultsError(error) } }


    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        fetchTrackings()
    }

    fun fetchTrackings() {
        job = Job()
        job = launch(refreshExceptionHandler) {
            withContext(Dispatchers.IO) {
                registerMdList(manga)
                trackList = db.getTracks(manga).executeAsBlocking().map { track ->
                    TrackItem(track, validServices.find { it.id == track.sync_id }!!)
                }
            }
            withContext(Dispatchers.Main)
            {
                view?.onNextTrackings(trackList)
            }
        }
    }

    fun refresh() {
        job = Job()
        job = launch(refreshExceptionHandler) {
            withContext(Dispatchers.IO) {
                refreshMdList(trackList.find { it.service.isMdList() }!!.track!!)

                trackList.filter { it.track != null && !it.service.isExternalLink() && !it.service.isMdList() }
                        .forEach { item ->
                            val refreshed = item.service.refresh(item.track!!)
                            db.insertTrack(refreshed).executeAsBlocking()
                        }
            }
            withContext(Dispatchers.Main)
            {
                view?.onRefreshDone()
            }
        }
    }

    private suspend fun refreshMdList(track: Track) {
        val remoteTrack = mdex.fetchTrackingInfo(manga)
        track.copyPersonalFrom(remoteTrack)
        track.total_chapters = remoteTrack.total_chapters
        db.insertTrack(track).executeAsBlocking()
    }

    fun search(query: String, service: TrackService) {
        job = Job()
        job = launch(searchExceptionHandler) {

            val search = withContext(Dispatchers.IO) {
                service.search(query)
            }
            withContext(Dispatchers.Main) {
                view?.onSearchResults(search)
            }
        }
    }

    private suspend fun registerMdList(manga: Manga) {
        validServices = trackManager.services.filter { it.isLogged || it.isExternalLink() }
        val tracksInDb = db.getTracks(manga).executeAsBlocking()

        val mdTrackCount = tracksInDb.filter { it.sync_id == TrackManager.MDLIST }.count()
        if (mdTrackCount == 0) {
            val track = mdex.fetchTrackingInfo(manga)
            track.manga_id = manga.id!!
            db.insertTrack(track).executeAsBlocking()
        }

        if (manga.anime_planet_id == null) {
            validServices = validServices.filter { it.id != TrackManager.ANIMEPLANET }
        } else {
            registerExternal(TrackManager.ANIMEPLANET, tracksInDb, AnimePlanet.URL, manga.anime_planet_id!!)
        }
        if (manga.manga_updates_id == null) {
            validServices = validServices.filter { it.id != TrackManager.MANGAUPDATES }
        } else {
            registerExternal(TrackManager.MANGAUPDATES, tracksInDb, MangaUpdates.URL, manga.manga_updates_id!!)
        }
    }

    fun registerExternal(serviceId: Int, tracksInDb: List<Track>, url: String, idForService: String) {
        val trackCount = tracksInDb.filter { it.sync_id == serviceId }.count()
        if (trackCount == 0) {
            val track = Track.create(serviceId)
            track.tracking_url = url + idForService
            track.title = manga.title
            track.manga_id = manga.id!!
            db.insertTrack(track).executeAsBlocking()
        }
    }

    fun registerTracking(item: Track?, service: TrackService) {
        job = Job()
        job = launch(refreshExceptionHandler) {
            withContext(Dispatchers.IO) {
                if (item != null) {
                    val track = service.bind(item)
                    db.insertTrack(track).executeAsBlocking()
                } else {
                    db.deleteTrackForManga(manga, service).executeAsBlocking()
                }

            }
            withContext(Dispatchers.Main) {
                view?.onRefreshDone()
            }
        }
    }

    private fun updateRemote(track: Track, service: TrackService) {
        job = Job()
        job = launch(refreshExceptionHandler) {
            withContext(Dispatchers.IO) {
                if (service.isMdList()) {
                    updateMdList(track)
                } else if (!service.isExternalLink()) {
                    val newTrack = service.update(track)
                    db.insertTrack(newTrack).executeAsBlocking()
                }
            }
            withContext(Dispatchers.Main) {
                view?.onRefreshDone()
                fetchTrackings()
            }
        }
    }

    /**
     * This updates MDList Tracker
     */
    private suspend fun updateMdList(track: Track) {
        val followStatus = FollowStatus.fromInt(track.status)!!

        //allow follow status to update
        if (manga.follow_status != followStatus) {
            mdex.updateFollowStatus(MdUtil.getMangaId(track.tracking_url), followStatus)
            manga.follow_status = followStatus
            db.insertManga(manga).executeAsBlocking()
        }

        //mangadex wont update chapters if manga is not follows this prevents unneeded network call
        if (followStatus != FollowStatus.UNFOLLOWED) {
            mdex.updateReadingProgress(track)
        }
        // insert the changes into tracking db
        db.insertTrack(track).executeAsBlocking()
    }

    fun setStatus(item: TrackItem, index: Int) {
        val track = item.track!!
        track.status = item.service.getStatusList()[index]
        //zero out tracking since mdlist zeros out on their website when you switch to unfollowed
        if (item.service.isMdList() && track.status == FollowStatus.UNFOLLOWED.int) {
            track.last_chapter_read = 0
        }
        updateRemote(track, item.service)
    }

    fun setScore(item: TrackItem, index: Int) {
        val track = item.track!!
        track.score = item.service.indexToScore(index)
        updateRemote(track, item.service)
    }

    fun setLastChapterRead(item: TrackItem, chapterNumber: Int) {
        val track = item.track!!
        var shouldUpdate = true
        //mangadex doesnt allow chapters to be updated if manga is unfollowed
        if (item.service.isMdList() && track.status == FollowStatus.UNFOLLOWED.int) {
            shouldUpdate = false
            view?.onRefreshDone()
        }
        if (shouldUpdate) {
            track.last_chapter_read = chapterNumber
            updateRemote(track, item.service)
        }
    }

}