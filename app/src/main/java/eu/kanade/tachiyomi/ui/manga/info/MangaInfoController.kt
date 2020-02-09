package eu.kanade.tachiyomi.ui.manga.info

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.transition.ChangeBounds
import androidx.transition.ChangeImageTransform
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.jakewharton.rxbinding.support.v4.widget.refreshes
import com.jakewharton.rxbinding.view.clicks
import com.jakewharton.rxbinding.view.longClicks
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import com.mikepenz.iconics.utils.colorInt
import com.mikepenz.iconics.utils.sizeDp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.controller.NucleusController
import eu.kanade.tachiyomi.ui.library.ChangeMangaCategoriesDialog
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.manga.MangaController
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.getUriCompat
import eu.kanade.tachiyomi.util.snack
import eu.kanade.tachiyomi.util.toast
import eu.kanade.tachiyomi.util.truncateCenter
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.android.synthetic.main.manga_info_controller.*
import timber.log.Timber
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * Fragment that shows manga information.
 * Uses R.layout.manga_info_controller.
 * UI related actions should be called from here.
 */
class MangaInfoController : NucleusController<MangaInfoPresenter>(),
        ChangeMangaCategoriesDialog.Listener {

    /**
     * Preferences helper.
     */
    private val preferences: PreferencesHelper by injectLazy()

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd")

    // Hold a reference to the current animator,
    // so that it can be canceled mid-way.
    private var currentAnimator: Animator? = null

    // The system "short" animation time duration, in milliseconds. This
    // duration is ideal for subtle animations or animations that occur
    // very frequently.
    private var shortAnimationDuration: Int = 0

    private var setUpFullCover = false

    var fullRes: Drawable? = null

    init {
        setHasOptionsMenu(true)
        setOptionsMenuHidden(true)
    }

    override fun createPresenter(): MangaInfoPresenter {
        val ctrl = parentController as MangaController
        return MangaInfoPresenter(ctrl.manga!!, ctrl.source!!,
                ctrl.chapterCountRelay, ctrl.lastUpdateRelay, ctrl.mangaFavoriteRelay)
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.manga_info_controller, container, false)
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        setUpFullCover = false
        // Set onclickListener to toggle favorite when FAB clicked.
        fab_favorite.clicks().subscribeUntilDestroy { onFabClick() }

        // Set onLongClickListener to manage categories when FAB is clicked.
        fab_favorite.longClicks().subscribeUntilDestroy { onFabLongClick() }

        // Set SwipeRefresh to refresh manga data.
        swipe_refresh.refreshes().subscribeUntilDestroy { fetchMangaFromSource() }

        manga_full_title.longClicks().subscribeUntilDestroy {
            copyToClipboard(view.context.getString(R.string.title), manga_full_title.text.toString())
        }

        /*manga_full_title.clicks().subscribeUntilDestroy {
           performGlobalSearch(manga_full_title.text.toString())
        }*/

        manga_artist.longClicks().subscribeUntilDestroy {
            copyToClipboard(manga_artist_label.text.toString(), manga_artist.text.toString())
        }

        /* manga_artist.clicks().subscribeUntilDestroy {
             performGlobalSearch(manga_artist.text.toString())
         }*/

        manga_author.longClicks().subscribeUntilDestroy {
            copyToClipboard(manga_author.text.toString(), manga_author.text.toString())
        }

        /* manga_author.clicks().subscribeUntilDestroy {
             performGlobalSearch(manga_author.text.toString())
         }*/

        manga_summary.longClicks().subscribeUntilDestroy {
            copyToClipboard(view.context.getString(R.string.description), manga_summary.text.toString())
        }

        manga_genres_tags.setOnTagClickListener { tag -> performLocalSearch(tag) }

        manga_cover.clicks().subscribeUntilDestroy {
            if (manga_cover.drawable != null) zoomImageFromThumb(manga_cover, manga_cover.drawable)
        }

        // Retrieve and cache the system's default "short" animation time.
        shortAnimationDuration = resources?.getInteger(android.R.integer.config_shortAnimTime) ?: 0

        manga_cover.longClicks().subscribeUntilDestroy {
            copyToClipboard(view.context.getString(R.string.title), presenter.manga.title)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.manga_info, menu)
        menu.findItem(R.id.action_share).icon =
                IconicsDrawable(applicationContext!!)
                        .icon(CommunityMaterial.Icon2.cmd_share_variant)
                        .sizeDp(18)
                        .colorInt(Color.WHITE)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_open_in_web_view -> openInWebView()
            R.id.action_share -> prepareToShareManga()
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Check if manga is initialized.
     * If true update view with manga information,
     * if false fetch manga information
     *
     * @param manga  manga object containing information about manga.
     * @param source the source of the manga.
     */
    fun onNextManga(manga: Manga, source: Source) {
        if (manga.initialized) {
            // Update view.
            setMangaInfo(manga)

        } else {
            // Initialize manga.
            fetchMangaFromSource()
        }
    }

    /**
     * Update the view with manga information.
     *
     * @param manga manga object containing information about manga.
     * @param source the source of the manga.
     */
    private fun setMangaInfo(manga: Manga) {
        val view = view ?: return

        try {
            //update full title TextView.
            manga_full_title.text = if (manga.title.isBlank()) {
                view.context.getString(R.string.unknown)
            } else {
                manga.title
            }

            // Update artist TextView.
            manga_artist.text = if (manga.artist.isNullOrBlank()) {
                view.context.getString(R.string.unknown)
            } else {
                manga.artist
            }

            // Update author TextView.
            manga_author.text = if (manga.author.isNullOrBlank()) {
                view.context.getString(R.string.unknown)
            } else {
                manga.author
            }

            // If manga lang flag is known
            manga_lang_flag.visibility = View.VISIBLE
            when (manga.lang_flag?.toLowerCase(Locale.US)) {
                "cn" -> manga_lang_flag.setImageResource(R.drawable.ic_flag_china);
                "kr" -> manga_lang_flag.setImageResource(R.drawable.ic_flag_korea);
                "jp" -> manga_lang_flag.setImageResource(R.drawable.ic_flag_japan);
                else -> manga_lang_flag.visibility = View.GONE
            }
            // Update genres list
            if (manga.genre.isNullOrBlank().not()) {
                manga_genres_tags.setTags(manga.genre?.split(", "))
            }

            // Update description TextView.
            manga_summary.text = if (manga.description.isNullOrBlank()) {
                view.context.getString(R.string.unknown)
            } else {
                manga.description
            }

            // Update status TextView.
            manga_status.setText(when (manga.status) {
                SManga.ONGOING -> R.string.ongoing
                SManga.COMPLETED -> R.string.completed
                SManga.LICENSED -> R.string.licensed
                SManga.PUBLICATION_COMPLETE -> R.string.publication_complete
                SManga.HIATUS -> R.string.hiatus
                SManga.CANCELLED -> R.string.cancelled
                else -> R.string.unknown
            })

            // Set the favorite drawable to the correct one.
            setFavoriteDrawable(manga.favorite)

            setCover(manga)
        } catch (e: Exception) {
            Timber.e(e)
        }

    }

    private fun switchProgressBar(visibility: Int) {
        manga_info_progress_bar?.visibility = visibility
    }

    private fun setCover(manga: Manga) {
        val view = view ?: return
        // Set cover if it wasn't already.
        if (manga_cover.drawable == null && !manga.thumbnail_url.isNullOrEmpty()) {
            GlideApp.with(view.context)
                    .load(manga)
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .centerCrop()
                    .into(manga_cover)
            if (manga_cover_full != null) {
                GlideApp.with(view.context).asDrawable().load(manga)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .override(CustomTarget.SIZE_ORIGINAL, CustomTarget.SIZE_ORIGINAL)
                        .into(object : CustomTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable,
                                                         transition: Transition<in Drawable>?
                            ) {
                                fullRes = resource
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {}
                        })
            }

            if (backdrop != null) {
                GlideApp.with(view.context)
                        .load(manga)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .centerCrop()
                        .into(backdrop)
            }
        }
    }

    override fun onDestroyView(view: View) {
        manga_genres_tags.setOnTagClickListener(null)
        super.onDestroyView(view)
    }

    /**
     * Update chapter count TextView.
     *
     * @param count number of chapters.
     */
    fun setChapterCount(count: Float) {
        if (count > 0f) {
            manga_chapters?.text = DecimalFormat("#.#").format(count)
        } else {
            manga_chapters?.text = resources?.getString(R.string.unknown)
        }
    }

    fun setLastUpdateDate(date: Date) {
        if (date.time != 0L) {
            manga_last_update?.text = dateFormat.format(date)
        } else {
            manga_last_update?.text = resources?.getString(R.string.unknown)
        }
    }

    /**
     * Toggles the favorite status and asks for confirmation to delete downloaded chapters.
     */
    private fun toggleFavorite() {
        val view = view

        val isNowFavorite = presenter.toggleFavorite()
        if (view != null && !isNowFavorite && presenter.hasDownloads()) {
            view.snack(view.context.getString(R.string.delete_downloads_for_manga)) {
                setAction(R.string.action_delete) {
                    presenter.deleteDownloads()
                }
            }
        }
    }

    private fun openInWebView() {
        val source = presenter.source as? HttpSource ?: return

        val url = try {
            source.mangaDetailsRequest(presenter.manga).url.toString()
        } catch (e: Exception) {
            return
        }

        val activity = activity ?: return
        val intent = WebViewActivity.newIntent(activity, source.id, url, presenter.manga.title)
        startActivity(intent)
    }

    /**
     * Called to run Intent with [Intent.ACTION_SEND], which show share dialog.
     */
    private fun prepareToShareManga() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && manga_cover.drawable != null)
            GlideApp.with(activity!!).asBitmap().load(presenter.manga).into(object :
                    CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    presenter.shareManga(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    shareManga()
                }
            })
        else shareManga()
    }

    /**
     * Called to run Intent with [Intent.ACTION_SEND], which show share dialog.
     */
    fun shareManga(cover: File? = null) {
        val context = view?.context ?: return

        val source = presenter.source as? HttpSource ?: return
        val stream = cover?.getUriCompat(context)
        try {
            val url = source.mangaDetailsRequest(presenter.manga).url.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/*"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_TITLE, presenter.manga.title)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (stream != null) {
                    clipData = ClipData.newRawUri(null, stream)
                }
            }
            startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Update FAB with correct drawable.
     *
     * @param isFavorite determines if manga is favorite or not.
     */
    private fun setFavoriteDrawable(isFavorite: Boolean) {
        // Set the Favorite drawable to the correct one.
        // Border drawable if false, filled drawable if true.

        fab_favorite?.setImageDrawable(
                if (isFavorite) {
                    IconicsDrawable(applicationContext!!)
                            .icon(CommunityMaterial.Icon2.cmd_heart)
                            .colorInt(Color.WHITE)
                            .sizeDp(20)
                } else {
                    IconicsDrawable(applicationContext!!)
                            .icon(CommunityMaterial.Icon2.cmd_heart_outline)
                            .colorInt(Color.WHITE)
                            .sizeDp(20)
                })
    }

    /**
     * Start fetching manga information from source.
     */
    private fun fetchMangaFromSource() {
        setRefreshing(true)
        manga_cover.setImageResource(0)
        // Use safe call, backdrop view does not exist in landscape mode
        backdrop?.setImageResource(0)
        // Call presenter and start fetching manga information
        presenter.fetchMangaFromSource()
    }


    /**
     * Update swipe refresh to stop showing refresh in progress spinner.
     */
    fun onFetchMangaDone() {
        setRefreshing(false)
    }


    /**
     * Update swipe refresh to start showing refresh in progress spinner.
     */
    fun onFetchMangaError(error: Throwable) {
        setRefreshing(false)
        Timber.e(error)
        activity?.toast(error.message)
    }

    /**
     * Set swipe refresh status.
     *
     * @param value whether it should be refreshing or not.
     */
    private fun setRefreshing(value: Boolean) {
        swipe_refresh?.isRefreshing = value
    }

    /**
     * Called when the fab is clicked.
     */
    private fun onFabClick() {
        val manga = presenter.manga
        toggleFavorite()
        if (manga.favorite) {
            val categories = presenter.getCategories()
            val defaultCategoryId = preferences.defaultCategory()
            val defaultCategory = categories.find { it.id == defaultCategoryId }
            when {
                defaultCategory != null -> presenter.moveMangaToCategory(manga, defaultCategory)
                defaultCategoryId == 0 || categories.isEmpty() -> // 'Default' or no category
                    presenter.moveMangaToCategory(manga, null)
                else -> {
                    val ids = presenter.getMangaCategoryIds(manga)
                    val preselected = ids.mapNotNull { id ->
                        categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
                    }.toTypedArray()

                    ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
                            .showDialog(router)
                }
            }
            activity?.toast(activity?.getString(R.string.manga_added_library))
        } else {
            activity?.toast(activity?.getString(R.string.manga_removed_library))
        }
    }

    /**
     * Called when the fab is long clicked.
     */
    private fun onFabLongClick() {
        val manga = presenter.manga
        if (!manga.favorite) {
            toggleFavorite()
            activity?.toast(activity?.getString(R.string.manga_added_library))
        }
        val categories = presenter.getCategories()
        if (categories.isEmpty()) {
            // no categories exist, display a message about adding categories
            activity?.toast(activity?.getString(R.string.action_add_category))
        } else {
            val ids = presenter.getMangaCategoryIds(manga)
            val preselected = ids.mapNotNull { id ->
                categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
            }.toTypedArray()

            ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
                    .showDialog(router)
        }
    }

    override fun updateCategoriesForMangas(mangas: List<Manga>, categories: List<Category>) {
        val manga = mangas.firstOrNull() ?: return
        presenter.moveMangaToCategories(manga, categories)
    }

    /**
     * Copies a string to clipboard
     *
     * @param label Label to show to the user describing the content
     * @param content the actual text to copy to the board
     */
    private fun copyToClipboard(label: String, content: String) {
        if (content.isBlank()) return

        val activity = activity ?: return
        val view = view ?: return

        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, content))

        activity.toast(view.context.getString(R.string.copied_to_clipboard, content.truncateCenter(20)),
                Toast.LENGTH_SHORT)
    }

    /**
     * Perform a local search using the provided query.
     *
     * @param query the search query to pass to the library controller
     */
    private fun performLocalSearch(query: String) {
        val router = parentController?.router ?: return
        val firstController = router.backstack.first()?.controller()
        if (firstController is LibraryController && router.backstack.size == 2) {
            router.handleBack()
            firstController.search(query)
        }
    }

    /**
     * Create shortcut using ShortcutManager.
     *
     * @param icon The image of the shortcut.
     */
    private fun createShortcut(icon: Bitmap) {
        val activity = activity ?: return
        val mangaControllerArgs = parentController?.args ?: return

        // Create the shortcut intent.
        val shortcutIntent = activity.intent
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MangaController.MANGA_EXTRA,
                        mangaControllerArgs.getLong(MangaController.MANGA_EXTRA))

        // Check if shortcut placement is supported
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            val shortcutId = "manga-shortcut-${presenter.manga.title}-${presenter.source.name}"

            // Create shortcut info
            val shortcutInfo = ShortcutInfoCompat.Builder(activity, shortcutId)
                    .setShortLabel(presenter.manga.title)
                    .setIcon(IconCompat.createWithBitmap(icon))
                    .setIntent(shortcutIntent)
                    .build()

            val successCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Create the CallbackIntent.
                val intent = ShortcutManagerCompat.createShortcutResultIntent(activity, shortcutInfo)

                // Configure the intent so that the broadcast receiver gets the callback successfully.
                PendingIntent.getBroadcast(activity, 0, intent, 0)
            } else {
                NotificationReceiver.shortcutCreatedBroadcast(activity)
            }

            // Request shortcut.
            ShortcutManagerCompat.requestPinShortcut(activity, shortcutInfo,
                    successCallback.intentSender)
        }
    }

    private fun setFullCoverToThumb() {
        if (setUpFullCover) return
        val expandedImageView = manga_cover_full ?: return
        val thumbView = manga_cover
        expandedImageView.pivotX = 0f
        expandedImageView.pivotY = 0f

        val layoutParams = expandedImageView.layoutParams
        layoutParams.height = thumbView.height
        layoutParams.width = thumbView.width
        expandedImageView.layoutParams = layoutParams
        expandedImageView.scaleType = ImageView.ScaleType.FIT_CENTER
        setUpFullCover = thumbView.height > 0
    }

    override fun handleBack(): Boolean {
        if (manga_cover_full?.visibility == View.VISIBLE && activity?.tabs?.selectedTabPosition
                == 0) {
            manga_cover_full?.performClick()
            return true
        }
        return super.handleBack()
    }

    private fun zoomImageFromThumb(thumbView: ImageView, cover: Drawable) {
        // If there's an animation in progress, cancel it immediately and proceed with this one.
        currentAnimator?.cancel()

        // Load the high-resolution "zoomed-in" image.
        val expandedImageView = manga_cover_full ?: return
        val fullBackdrop = full_backdrop
        val image = fullRes ?: return
        expandedImageView.setImageDrawable(image)

        // Hide the thumbnail and show the zoomed-in view. When the animation
        // begins, it will position the zoomed-in view in the place of the
        // thumbnail.
        thumbView.alpha = 0f
        expandedImageView.visibility = View.VISIBLE
        fullBackdrop.visibility = View.VISIBLE

        // Set the pivot point to 0 to match thumbnail

        swipe_refresh.isEnabled = false

        val layoutParams = expandedImageView.layoutParams
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        expandedImageView.layoutParams = layoutParams

        // TransitionSet for the full cover because using animation for this SUCKS
        val transitionSet = TransitionSet()
        val bound = ChangeBounds()
        transitionSet.addTransition(bound)
        val changeImageTransform = ChangeImageTransform()
        transitionSet.addTransition(changeImageTransform)
        transitionSet.duration = shortAnimationDuration.toLong()
        TransitionManager.beginDelayedTransition(manga_info_layout, transitionSet)

        // AnimationSet for backdrop because idk how to use TransitionSet
        currentAnimator = AnimatorSet().apply {
            play(
                    ObjectAnimator.ofFloat(fullBackdrop, View.ALPHA, 0f, 0.5f)
            )
            duration = shortAnimationDuration.toLong()
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {

                override fun onAnimationEnd(animation: Animator) {
                    TransitionManager.endTransitions(manga_info_layout)
                    currentAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    TransitionManager.endTransitions(manga_info_layout)
                    currentAnimator = null
                }
            })
            start()
        }

        expandedImageView.setOnClickListener {
            currentAnimator?.cancel()

            val layoutParams = expandedImageView.layoutParams
            layoutParams.height = thumbView.height
            layoutParams.width = thumbView.width
            expandedImageView.layoutParams = layoutParams

            // Zoom out back to tc thumbnail
            val transitionSet = TransitionSet()
            val bound = ChangeBounds()
            transitionSet.addTransition(bound)
            val changeImageTransform = ChangeImageTransform()
            transitionSet.addTransition(changeImageTransform)
            transitionSet.duration = shortAnimationDuration.toLong()
            TransitionManager.beginDelayedTransition(manga_info_layout, transitionSet)

            // Animation to remove backdrop and hide the full cover
            currentAnimator = AnimatorSet().apply {
                play(ObjectAnimator.ofFloat(fullBackdrop, View.ALPHA, 0f))
                duration = shortAnimationDuration.toLong()
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {

                    override fun onAnimationEnd(animation: Animator) {
                        thumbView.alpha = 1f
                        expandedImageView.visibility = View.GONE
                        fullBackdrop.visibility = View.GONE
                        swipe_refresh.isEnabled = true
                        currentAnimator = null
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        thumbView.alpha = 1f
                        expandedImageView.visibility = View.GONE
                        fullBackdrop.visibility = View.GONE
                        swipe_refresh.isEnabled = true
                        currentAnimator = null
                    }
                })
                start()
            }
        }
    }


}
