package eu.kanade.tachiyomi.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.biometric.BiometricManager
import com.bluelinelabs.conductor.Conductor
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.Router
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.typeface.IIcon
import com.mikepenz.iconics.typeface.library.community.material.CommunityMaterial
import eu.kanade.tachiyomi.Migrations
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.getOrDefault
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NoToolbarElevationController
import eu.kanade.tachiyomi.ui.base.controller.TabbedController
import eu.kanade.tachiyomi.ui.base.controller.withFadeTransaction
import eu.kanade.tachiyomi.ui.catalogue.browse.BrowseCatalogueController
import eu.kanade.tachiyomi.ui.download.DownloadController
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.setting.SettingsMainController
import eu.kanade.tachiyomi.widget.preference.MangadexLoginDialog
import kotlinx.android.synthetic.main.main_activity.*
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.*


class MainActivity : BaseActivity(), MangadexLoginDialog.Listener {

    private lateinit var router: Router

    val source: Source by lazy { Injekt.get<SourceManager>().getSources()[0] }

    lateinit var tabAnimator: TabsAnimator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        setContentView(R.layout.main_activity)

        setSupportActionBar(toolbar)

        tabAnimator = TabsAnimator(tabs)

        // Set behavior of Navigation drawer
        addIconToMenu(R.id.nav_drawer_library, CommunityMaterial.Icon2.cmd_library)
        addIconToMenu(R.id.nav_drawer_browse, CommunityMaterial.Icon.cmd_compass_outline)
        addIconToMenu(R.id.nav_drawer_downloads, CommunityMaterial.Icon.cmd_download)
        addIconToMenu(R.id.nav_drawer_settings, CommunityMaterial.Icon2.cmd_settings)


        bottom_navigation.setOnNavigationItemSelectedListener { item ->
            val id = item.itemId

            val currentRoot = router.backstack.firstOrNull()
            if (currentRoot?.tag()?.toIntOrNull() != id) {
                when (id) {
                    R.id.nav_drawer_library -> setRoot(LibraryController(), id)
                    R.id.nav_drawer_browse -> {
                        val browseCatalogueController = BrowseCatalogueController(source)
                        if (!source.isLogged()) {
                            val dialog = MangadexLoginDialog(source)
                            dialog.targetController = browseCatalogueController
                            dialog.showDialog(router)
                            bottom_navigation.menu.getItem(0).isChecked = true
                        } else {
                            setRoot(browseCatalogueController, id)
                        }
                    }
                    R.id.nav_drawer_downloads -> {
                        setRoot(DownloadController(), id)
                    }
                    R.id.nav_drawer_settings -> {
                        setRoot(SettingsMainController(), id)
                    }
                }
            }
            true
        }

        val container: ViewGroup = findViewById(R.id.controller_container)

        router = Conductor.attachRouter(this, container, savedInstanceState)

        if (!router.hasRootController()) {
            // Set start screen
            bottom_navigation.selectedItemId = R.id.nav_drawer_library
        }

        router.addChangeListener(object : ControllerChangeHandler.ControllerChangeListener {
            override fun onChangeStarted(to: Controller?, from: Controller?, isPush: Boolean,
                                         container: ViewGroup, handler: ControllerChangeHandler) {

                syncActivityViewWithController(to, from)
            }

            override fun onChangeCompleted(to: Controller?, from: Controller?, isPush: Boolean,
                                           container: ViewGroup, handler: ControllerChangeHandler) {

            }

        })

        syncActivityViewWithController(router.backstack.lastOrNull()?.controller())

        if (savedInstanceState == null) {
            // Show changelog if needed
            if (Migrations.upgrade(preferences)) {
                ChangelogDialogController().showDialog(router)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val useBiometrics = preferences.useBiometrics().getOrDefault()
        if (useBiometrics && BiometricManager.from(this)
                        .canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS) {
            if (!unlocked && (preferences.lockAfter().getOrDefault() <= 0 || Date().time >=
                            preferences.lastUnlock().getOrDefault() + 60 * 1000 * preferences.lockAfter().getOrDefault())) {
                val intent = Intent(this, BiometricActivity::class.java)
                startActivity(intent)
                this.overridePendingTransition(0, 0)
            }
        } else if (useBiometrics)
            preferences.useBiometrics().set(false)
    }

    private fun addIconToMenu(nav_drawer_library: Int, icon: IIcon) {
        //no size or color needed since navigation drawer dictates it
        bottom_navigation.menu.findItem(nav_drawer_library).icon = IconicsDrawable(this).icon(icon)

    }

    /**
     * Called when login dialog is closed, refreshes the adapter.
     *
     * @param source clicked item containing source information.
     */
    override fun siteLoginDialogClosed(source: Source) {
        if (source.isLogged()) {
            router.popCurrentController()
            R.id.nav_drawer_browse
            setRoot(BrowseCatalogueController(source), R.id.nav_drawer_browse)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        toolbar?.setNavigationOnClickListener(null)
    }

    override fun onBackPressed() {
        val backstackSize = router.backstackSize
        if (backstackSize == 1 || !router.handleBack()) {
            unlocked = false
            super.onBackPressed()
        }
    }

    private fun setRoot(controller: Controller, id: Int) {
        router.setRoot(controller.withFadeTransaction().tag(id.toString()))
    }

    @SuppressLint("ObjectAnimatorBinding")
    private fun syncActivityViewWithController(to: Controller?, from: Controller? = null) {
        if (from is DialogController || to is DialogController) {
            return
        }


        if (from is TabbedController) {
            from.cleanupTabs(tabs)
        }
        if (to is TabbedController) {
            tabAnimator.expand()
            to.configureTabs(tabs)
        } else {
            tabAnimator.collapse()
            tabs.setupWithViewPager(null)
        }

        if (to is NoToolbarElevationController) {
            appbar.disableElevation()
        } else {
            appbar.enableElevation()
        }
    }

    companion object {
        // Shortcut actions
        var unlocked = false
    }

}
