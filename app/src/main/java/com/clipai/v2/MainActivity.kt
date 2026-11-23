package com.clipai.v2

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesomeMotion
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.clipai.v2.ui.MainViewModel
import com.clipai.v2.ui.screens.ClipsScreen
import com.clipai.v2.ui.screens.EditorScreen
import com.clipai.v2.ui.screens.HomeProjectsScreen
import com.clipai.v2.ui.screens.SettingsScreen
import com.clipai.v2.ui.theme.ClipAiTheme
import com.clipai.v2.ui.theme.StudioColors

private enum class TopTab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home),
    CLIPS("clips", "Clips", Icons.Outlined.AutoAwesomeMotion),
    SETTINGS("settings", "Settings", Icons.Outlined.Tune)
}

private const val ROUTE_EDITOR = "editor"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClipAiTheme {
                ClipAiAppShell()
            }
        }
    }
}

@Composable
private fun ClipAiAppShell(vm: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: TopTab.HOME.route

    val projects by vm.projects.collectAsStateWithLifecycle()
    val activeProject by vm.activeProject.collectAsStateWithLifecycle()
    val activeClips by vm.activeClips.collectAsStateWithLifecycle()
    val activeWords by vm.activeWords.collectAsStateWithLifecycle()
    val selectedClip by vm.selectedClip.collectAsStateWithLifecycle()
    val pipelineProgress by vm.pipelineProgress.collectAsStateWithLifecycle()
    val deviceCaps by vm.deviceCapabilities.collectAsStateWithLifecycle()
    val fallbackPlan by vm.fallbackPlan.collectAsStateWithLifecycle()
    val modelStates by vm.modelStates.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasHfToken by vm.hasHfToken.collectAsStateWithLifecycle()
    val exportState by vm.exportState.collectAsStateWithLifecycle()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val navigateTab: (TopTab) -> Unit = { tab ->
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val showBottomBar = currentRoute != ROUTE_EDITOR

    Scaffold(
        containerColor = StudioColors.ObsidianCanvas,
        bottomBar = {
            if (showBottomBar) {
                Column {
                    HorizontalDivider(thickness = 1.dp, color = StudioColors.HairlineBorder)
                    NavigationBar(
                        containerColor = StudioColors.SurfaceCard,
                        tonalElevation = 0.dp
                    ) {
                        TopTab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTab(tab) },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = StudioColors.AccentAmber,
                                    selectedTextColor = StudioColors.AccentAmber,
                                    unselectedIconColor = StudioColors.TextSecondary,
                                    unselectedTextColor = StudioColors.TextSecondary,
                                    indicatorColor = StudioColors.SurfaceElevated
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopTab.HOME.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopTab.HOME.route) {
                HomeProjectsScreen(
                    projects = projects,
                    activeProjectId = activeProject?.id,
                    pipelineProgress = pipelineProgress,
                    device = deviceCaps,
                    fallbackPlan = fallbackPlan,
                    onPickVideoUri = { uri -> vm.startAutoClipForUri(uri) },
                    onIngestLink = { url, seconds -> vm.startAutoClipForLink(url, seconds) },
                    onCancelPipeline = { vm.cancelActivePipeline() },
                    onOpenProject = { projId ->
                        vm.selectProject(projId)
                        navigateTab(TopTab.CLIPS)
                    },
                    onDeleteProject = { projId -> vm.deleteProject(projId) }
                )
            }

            composable(TopTab.CLIPS.route) {
                ClipsScreen(
                    project = activeProject,
                    clips = activeClips,
                    fallbackPlan = fallbackPlan,
                    dedupGapSec = settings.dedupGapSec,
                    onOpenEditor = { clipId ->
                        vm.selectClip(clipId)
                        navController.navigate(ROUTE_EDITOR)
                    },
                    onGoProjects = { navigateTab(TopTab.HOME) }
                )
            }

            composable(ROUTE_EDITOR) {
                EditorScreen(
                    project = activeProject,
                    clips = activeClips.filter { it.selected },
                    selectedClip = selectedClip,
                    words = activeWords,
                    settings = settings,
                    exportState = exportState,
                    onSelectClip = { vm.selectClip(it) },
                    onUpdateCrop = { clipId, mode, offset -> vm.updateCropSettings(clipId, mode, offset) },
                    onUpdateHook = { clipId, mode, verbatim, title -> vm.updateHookSettings(clipId, mode, verbatim, title) },
                    onLockWordEdit = { wordId, text -> vm.lockWordEdit(wordId, text) },
                    onImportTranscript = { vm.importTranscriptAndRescore(it) },
                    onExportClip = { vm.exportSelectedClip() },
                    onCancelExport = { vm.cancelExport() },
                    onBuildShareIntent = { vm.buildShareIntent(it) },
                    onBack = { navController.popBackStack() },
                    onGoProjects = { navigateTab(TopTab.HOME) }
                )
            }

            composable(TopTab.SETTINGS.route) {
                SettingsScreen(
                    settings = settings,
                    device = deviceCaps,
                    fallbackPlan = fallbackPlan,
                    modelStates = modelStates,
                    hasHfToken = hasHfToken,
                    activeModelInRam = pipelineProgress.activeModelInRam,
                    onUpdateSettings = { vm.updateSettings(it) },
                    onDownloadModel = { vm.enqueueModelDownload(it) },
                    onPauseModel = { vm.pauseModelDownload(it) },
                    onDeleteModel = { vm.deleteModel(it) },
                    onSelectAsr = { id -> vm.updateSettings { it.copy(selectedAsrModelId = id) } },
                    onSelectLlm = { id -> vm.updateSettings { it.copy(selectedLlmModelId = id, llmEnabled = true) } },
                    onToggleLlmEnabled = { enabled -> vm.updateSettings { it.copy(llmEnabled = enabled) } },
                    onSaveHfToken = { vm.saveUserHfToken(it) },
                    onClearHfToken = { vm.clearUserHfToken() },
                    onRefreshDevice = { vm.refreshDeviceCapabilities() }
                )
            }
        }
    }
}
