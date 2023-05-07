package ua.com.radiokot.photoprism.features.gallery.data.storage

import androidx.collection.LruCache
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import ua.com.radiokot.photoprism.api.model.PhotoPrismOrder
import ua.com.radiokot.photoprism.api.photos.service.PhotoPrismPhotosService
import ua.com.radiokot.photoprism.base.data.model.DataPage
import ua.com.radiokot.photoprism.base.data.model.PagingOrder
import ua.com.radiokot.photoprism.base.data.storage.SimplePagedDataRepository
import ua.com.radiokot.photoprism.extension.*
import ua.com.radiokot.photoprism.features.gallery.data.model.GalleryMedia
import ua.com.radiokot.photoprism.features.gallery.data.model.SearchConfig
import ua.com.radiokot.photoprism.features.gallery.data.model.photoPrismDateFormat
import ua.com.radiokot.photoprism.features.gallery.logic.MediaFileDownloadUrlFactory
import ua.com.radiokot.photoprism.features.gallery.logic.MediaPreviewUrlFactory
import ua.com.radiokot.photoprism.features.gallery.logic.MediaWebUrlFactory
import java.util.*

class SimpleGalleryMediaRepository(
    private val photoPrismPhotosService: PhotoPrismPhotosService,
    private val thumbnailUrlFactory: MediaPreviewUrlFactory,
    private val downloadUrlFactory: MediaFileDownloadUrlFactory,
    private val webUrlFactory: MediaWebUrlFactory,
    val query: String?,
    pageLimit: Int,
) : SimplePagedDataRepository<GalleryMedia>(
    pagingOrder = PagingOrder.DESC,
    pageLimit = pageLimit,
) {
    private val log = kLogger("SimpleGalleryMediaRepo")

    // See .addNewPageItems for explanation.
    private val itemsByUid = mutableMapOf<String, GalleryMedia>()

    override fun getPage(
        limit: Int,
        cursor: String?,
        order: PagingOrder
    ): Single<DataPage<GalleryMedia>> {
        // Must not be changed to a set. Do not distinct items.
        // See .addNewPageItems for explanation.
        val collectedGalleryMediaItems = mutableListOf<GalleryMedia>()

        var nextCursor = cursor
        var offset = 0
        var pageIsLast = false

        val loadPage = {
            offset = nextCursor?.toInt() ?: 0

            log.debug {
                "getPage(): loading_page:" +
                        "\noffset=$offset," +
                        "\blimit=$pageLimit"
            }

            photoPrismPhotosService.getMergedPhotos(
                count = pageLimit,
                offset = offset,
                q = query,
                order = when (pagingOrder) {
                    PagingOrder.DESC -> PhotoPrismOrder.NEWEST
                    PagingOrder.ASC -> PhotoPrismOrder.OLDEST
                }
            )
        }
            .toSingle()
            .map { photoPrismPhotos ->
                val filesCount = photoPrismPhotos.sumOf { it.files.size }
                pageIsLast = filesCount < limit

                log.debug {
                    "getPage(): raw_page_loaded:" +
                            "\nfilesCount=${photoPrismPhotos.sumOf { it.files.size }}," +
                            "\npageIsLast=$pageIsLast"
                }

                photoPrismPhotos.mapSuccessful {
                    GalleryMedia(
                        source = it,
                        previewUrlFactory = thumbnailUrlFactory,
                        downloadUrlFactory = downloadUrlFactory,
                        webUrlFactory = webUrlFactory,
                    )
                }
            }
            .doOnSuccess { successfullyLoadedItems ->
                collectedGalleryMediaItems.addAll(successfullyLoadedItems)

                // Load extra data to fulfill the requested page limit.
                nextCursor = (limit + offset).toString()

                log.debug {
                    "getPage(): page_loaded:" +
                            "\nsuccessfullyLoadedItemsCount=${successfullyLoadedItems.size}," +
                            "\nexpectedCount=$pageLimit"
                }
            }

        return loadPage
            .repeatUntil { pageIsLast || collectedGalleryMediaItems.size >= pageLimit }
            .ignoreElements()
            .toSingle {
                log.debug {
                    "getPage(): loaded_enough_data:" +
                            "\nitemsCount=${collectedGalleryMediaItems.size}," +
                            "\nlimit=$limit"
                }

                DataPage(
                    items = collectedGalleryMediaItems,
                    nextCursor = nextCursor.checkNotNull {
                        "The cursor must be defined at this moment"
                    },
                    isLast = pageIsLast,
                )
            }
    }

    private var newestAndOldestDates: Pair<Date, Date>? = null
    fun getNewestAndOldestDates(): Maybe<Pair<Date, Date>> {
        val loadedDates = newestAndOldestDates
        if (loadedDates != null) {
            return Maybe.just(loadedDates)
        }

        val getNewestDate = {
            photoPrismPhotosService.getMergedPhotos(
                count = 1,
                offset = 0,
                q = query,
                order = PhotoPrismOrder.NEWEST
            )
                .firstOrNull()
                ?.takenAt
                ?.let(GalleryMedia.Companion::parsePhotoPrismDate)
        }.toMaybe()

        val getOldestDate = {
            photoPrismPhotosService.getMergedPhotos(
                count = 1,
                offset = 0,
                q = query,
                order = PhotoPrismOrder.OLDEST
            )
                .firstOrNull()
                ?.takenAt
                ?.let(GalleryMedia.Companion::parsePhotoPrismDate)
        }.toMaybe()

        return Maybe.zip(
            getNewestDate,
            getOldestDate,
            ::Pair
        )
            .doOnSuccess { newestAndOldestDates = it }
            .subscribeOn(Schedulers.io())
    }

    override fun addNewPageItems(page: DataPage<GalleryMedia>) {
        page.items.forEach { item ->
            if (itemsByUid.containsKey(item.uid)) {
                // If this item is already loaded, just merge the files. Why?
                // Scenario:
                // 1. Loaded a page of merged photos. PhotoPrism page limit limits number of files, not photos;
                // 2. Last page photo is happened to be a video, it has 2 files (preview and video);
                // 3. Because of the limit, only the first file is returned (preview);
                // 4. Now in the repository we have an item with only one file.
                //    Until the next page is loaded, this item is in some way broken;
                // 5. Loaded the next page. The first photo is the same video, but now
                //    it contains only the second file (video);
                // 5. Ha, we've caught that! Merging the files;
                // 6. Now the existing item has all the required files.
                //
                // More reliable workaround is not to fetch files from the merged photos pages,
                // but to load them on demand through the /view endpoint.
                // But I think this doesn't worth it.

                itemsByUid.getValue(item.uid).mergeFiles(item.files)

                log.debug {
                    "addNewPageItems(): merged_files:" +
                            "\nitemUid=${item.uid}"
                }
            } else {
                mutableItemsList.add(item)
                itemsByUid[item.uid] = item
            }
        }
    }

    override fun update(): Completable {
        newestAndOldestDates = null
        itemsByUid.clear()
        return super.update()
    }

    override fun invalidate() {
        newestAndOldestDates = null
        super.invalidate()
    }

    override fun toString(): String {
        return "SimpleGalleryMediaRepository(query=$query)"
    }

    class Factory(
        private val photoPrismPhotosService: PhotoPrismPhotosService,
        private val thumbnailUrlFactory: MediaPreviewUrlFactory,
        private val downloadUrlFactory: MediaFileDownloadUrlFactory,
        private val webUrlFactory: MediaWebUrlFactory,
        private val pageLimit: Int,
    ) {
        private val cache = LruCache<String, SimpleGalleryMediaRepository>(10)

        fun getForSearch(config: SearchConfig): SimpleGalleryMediaRepository {
            val queryBuilder = StringBuilder()

            // User query goes first, hence all the other params override the input.
            queryBuilder.append(" ${config.userQuery}")

            if (config.mediaTypes.isNotEmpty()) {
                queryBuilder.append(
                    " type:${
                        config.mediaTypes.joinToString("|") { it.value }
                    }"
                )
            }

            if (config.before != null) {
                synchronized(photoPrismDateFormat) {
                    queryBuilder.append(" before:\"${photoPrismDateFormat.format(config.before)}\"")
                }
            }

            queryBuilder.append(" public:${!config.includePrivate}")

            if (config.albumUid != null) {
                queryBuilder.append(" album:${config.albumUid}")
            }

            val query = queryBuilder.toString()
                .trim()
                .takeUnless(String::isNullOrBlank)

            return get(query)
        }

        fun get(query: String?): SimpleGalleryMediaRepository {
            val key = "q:$query"

            return cache[key]
                ?: SimpleGalleryMediaRepository(
                    photoPrismPhotosService = photoPrismPhotosService,
                    thumbnailUrlFactory = thumbnailUrlFactory,
                    downloadUrlFactory = downloadUrlFactory,
                    webUrlFactory = webUrlFactory,
                    query = query,
                    pageLimit = pageLimit,
                ).also {
                    cache.put(key, it)
                }
        }
    }
}