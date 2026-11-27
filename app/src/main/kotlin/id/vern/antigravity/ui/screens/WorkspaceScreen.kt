package id.vern.antigravity.ui.screens

import android.content.res.Configuration
import android.os.Environment
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.vern.antigravity.R
import id.vern.antigravity.ui.components.AgentDetailsPanelContent
import id.vern.antigravity.ui.components.CodeEditor
import id.vern.antigravity.ui.components.DiffViewer
import id.vern.antigravity.ui.components.FileTree
import id.vern.antigravity.ui.components.ModelSelectorDialog
import id.vern.antigravity.ui.components.TerminalView
import id.vern.antigravity.util.ExternalFileOpener
import id.vern.antigravity.viewmodel.AntigravityViewModel
import java.io.File

enum class ActiveToolView {
    CHAT,
    FILES,
    EDITOR,
    TERMINAL,
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: AntigravityViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val layoutDirection = LocalLayoutDirection.current
    var activeView by remember { mutableStateOf(ActiveToolView.CHAT) }
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val thinkingBudget by viewModel.thinkingBudget.collectAsStateWithLifecycle()
    val userQuotaSummary by viewModel.userQuotaSummary.collectAsStateWithLifecycle()
    val codeAssistTier by viewModel.codeAssistTier.collectAsStateWithLifecycle()
    val cliModels by viewModel.cliModels.collectAsStateWithLifecycle()
    val quotaErrorMessage by viewModel.quotaErrorMessage.collectAsStateWithLifecycle()
    var showModelDialog by remember { mutableStateOf(false) }

    var isRightPanelOpen by rememberSaveable { mutableStateOf(true) }
    var rightPanelTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingRightPanelFile by remember { mutableStateOf<String?>(null) }
    var openDrawerTrigger by remember { mutableIntStateOf(0) }
    var openSettingsTrigger by remember { mutableIntStateOf(0) }
    var isSettingsSheetOpen by remember { mutableStateOf(false) }

    val openTabs by viewModel.openEditorTabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeEditorTabId.collectAsStateWithLifecycle()

    val rootDir = remember {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (docs != null && docs.exists()) docs else (Environment.getExternalStorageDirectory() ?: File("/"))
    }

    fun openFile(file: File) {
        if (ExternalFileOpener.isExternalBinaryFile(file)) {
            ExternalFileOpener.openFile(context, file)
            return
        }
        viewModel.openFileInEditor(file)
        activeView = ActiveToolView.EDITOR
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp ||
            (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && maxWidth >= 520.dp)

        if (isWideScreen) {
            val isCompactHeight = maxHeight < 430.dp
            val showRailLabels = maxHeight >= 365.dp
            val headerHeight = if (isCompactHeight) 42.dp else 48.dp

            val cutoutPadding = WindowInsets.displayCutout.asPaddingValues()
            val cutoutStart = cutoutPadding.calculateStartPadding(layoutDirection)
            val cutoutEnd = cutoutPadding.calculateEndPadding(layoutDirection)

            val railBaseWidth = when {
                maxWidth < 680.dp -> 52.dp
                maxWidth < 960.dp -> 60.dp
                else -> 68.dp
            }
            val rightPanelRatio = when {
                maxWidth < 680.dp -> 0.38f
                maxWidth < 960.dp -> 0.37f
                else -> 0.35f
            }
            val usableWorkspaceWidth = (maxWidth - cutoutStart - cutoutEnd - railBaseWidth - 2.dp).coerceAtLeast(300.dp)
            val rightPanelContentWidth = (usableWorkspaceWidth * rightPanelRatio).coerceIn(216.dp, 400.dp)
            val rightPanelTotalWidth = rightPanelContentWidth + cutoutEnd

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clipToBounds(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest
                ) {
                    Column(
                        modifier = Modifier
                            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))
                            .width(railBaseWidth)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(headerHeight),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isCompactHeight) 36.dp else 42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { openDrawerTrigger++ },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_launcher_logo),
                                    contentDescription = stringResource(R.string.nav_drawer_toggle),
                                    modifier = Modifier.size(if (isCompactHeight) 21.dp else 24.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = if (isCompactHeight) 3.dp else 6.dp),
                            verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 1.dp else 3.dp, Alignment.CenterVertically),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val isChatFocused = !isSettingsSheetOpen && !isRightPanelOpen
                            WorkspaceRailItem(
                                selected = isChatFocused,
                                icon = if (isChatFocused) Icons.AutoMirrored.Filled.Chat else Icons.AutoMirrored.Outlined.Chat,
                                label = stringResource(R.string.nav_item_chat),
                                compact = isCompactHeight,
                                showLabel = showRailLabels,
                                railWidth = railBaseWidth,
                                onClick = {
                                    if (isRightPanelOpen) {
                                        isRightPanelOpen = false
                                    } else {
                                        openDrawerTrigger++
                                    }
                                }
                            )

                            WorkspaceRailItem(
                                selected = !isSettingsSheetOpen && isRightPanelOpen && rightPanelTab == 0,
                                icon = Icons.AutoMirrored.Filled.Article,
                                label = stringResource(R.string.agent_tab_overview),
                                compact = isCompactHeight,
                                showLabel = showRailLabels,
                                railWidth = railBaseWidth,
                                onClick = {
                                    if (isRightPanelOpen && rightPanelTab == 0) {
                                        isRightPanelOpen = false
                                    } else {
                                        rightPanelTab = 0
                                        isRightPanelOpen = true
                                    }
                                }
                            )

                            WorkspaceRailItem(
                                selected = !isSettingsSheetOpen && isRightPanelOpen && rightPanelTab == 1,
                                icon = Icons.Default.Difference,
                                label = stringResource(R.string.agent_tab_review),
                                compact = isCompactHeight,
                                showLabel = showRailLabels,
                                railWidth = railBaseWidth,
                                onClick = {
                                    if (isRightPanelOpen && rightPanelTab == 1) {
                                        isRightPanelOpen = false
                                    } else {
                                        rightPanelTab = 1
                                        isRightPanelOpen = true
                                    }
                                }
                            )

                            WorkspaceRailItem(
                                selected = !isSettingsSheetOpen && isRightPanelOpen && rightPanelTab == 2,
                                icon = Icons.Default.Terminal,
                                label = stringResource(R.string.nav_item_terminal),
                                compact = isCompactHeight,
                                showLabel = showRailLabels,
                                railWidth = railBaseWidth,
                                onClick = {
                                    if (isRightPanelOpen && rightPanelTab == 2) {
                                        isRightPanelOpen = false
                                    } else {
                                        rightPanelTab = 2
                                        isRightPanelOpen = true
                                    }
                                }
                            )

                            WorkspaceRailItem(
                                selected = !isSettingsSheetOpen && isRightPanelOpen && rightPanelTab == 3,
                                icon = Icons.Default.Folder,
                                label = stringResource(R.string.nav_item_files),
                                compact = isCompactHeight,
                                showLabel = showRailLabels,
                                railWidth = railBaseWidth,
                                onClick = {
                                    val currentEditorTab = openTabs.find { it.id == activeTabId }
                                    if (isRightPanelOpen && rightPanelTab == 3) {
                                        if (currentEditorTab != null) {
                                            viewModel.closeEditorTab(currentEditorTab.id)
                                        } else {
                                            isRightPanelOpen = false
                                        }
                                    } else {
                                        rightPanelTab = 3
                                        isRightPanelOpen = true
                                    }
                                }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))

                        Box(
                            modifier = Modifier.padding(vertical = if (isCompactHeight) 3.dp else 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            WorkspaceRailItem(
                                selected = isSettingsSheetOpen,
                                icon = Icons.Default.Settings,
                                label = stringResource(R.string.nav_item_settings),
                                compact = isCompactHeight,
                                showLabel = showRailLabels,
                                railWidth = railBaseWidth,
                                onClick = { openSettingsTrigger++ }
                            )
                        }
                    }
                }

                VerticalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .then(
                            if (!isRightPanelOpen) {
                                Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                            } else {
                                Modifier
                            }
                        )
                ) {
                    ChatScreen(
                        viewModel = viewModel,
                        onNavigateToSettings = { openSettingsTrigger++ },
                        onNavigateToFiles = {
                            rightPanelTab = 3
                            isRightPanelOpen = true
                        },
                        onNavigateToEditor = {
                            rightPanelTab = 3
                            isRightPanelOpen = true
                        },
                        onNavigateToTerminal = {
                            rightPanelTab = 2
                            isRightPanelOpen = true
                        },
                        isWideScreen = true,
                        isRightPanelOpen = isRightPanelOpen,
                        onToggleRightPanel = { isRightPanelOpen = !isRightPanelOpen },
                        onOpenRightPanelTab = { tab, filePath ->
                            if (!filePath.isNullOrBlank()) {
                                pendingRightPanelFile = filePath
                            }
                            rightPanelTab = tab
                            isRightPanelOpen = true
                        },
                        openDrawerRequestTrigger = openDrawerTrigger,
                        openSettingsRequestTrigger = openSettingsTrigger,
                        onSettingsSheetVisibleChange = { isOpen -> isSettingsSheetOpen = isOpen },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                AnimatedVisibility(
                    visible = isRightPanelOpen,
                    enter = expandHorizontally(
                        animationSpec = tween(durationMillis = 250),
                        expandFrom = Alignment.Start
                    ) + fadeIn(animationSpec = tween(durationMillis = 200)),
                    exit = shrinkHorizontally(
                        animationSpec = tween(durationMillis = 220),
                        shrinkTowards = Alignment.Start
                    ) + fadeOut(animationSpec = tween(durationMillis = 170))
                ) {
                    Row(
                        modifier = Modifier
                            .width(rightPanelTotalWidth)
                            .fillMaxHeight()
                            .clipToBounds()
                    ) {
                        VerticalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)
                        )
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            AgentDetailsPanelContent(
                                viewModel = viewModel,
                                selectedTab = rightPanelTab,
                                onSelectTab = { rightPanelTab = it },
                                initialFileToOpen = pendingRightPanelFile,
                                onInitialFileConsumed = { pendingRightPanelFile = null },
                                isDockedPanel = true,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
                                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical))
                            )
                        }
                    }
                }
            }
        } else {
            AnimatedContent(
                targetState = activeView,
                transitionSpec = {
                    if (targetState == ActiveToolView.CHAT) {
                        (slideInHorizontally(animationSpec = tween(300), initialOffsetX = { -it }) + fadeIn(tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(250), targetOffsetX = { it }) + fadeOut(tween(250)))
                    } else {
                        (slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it }) + fadeIn(tween(300)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(250), targetOffsetX = { -it }) + fadeOut(tween(250)))
                    }
                },
                label = "MobileToolViewTransition",
                modifier = Modifier.fillMaxSize()
            ) { view ->
                when (view) {
                    ActiveToolView.CHAT -> {
                        ChatScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { activeView = ActiveToolView.SETTINGS },
                            onNavigateToFiles = { activeView = ActiveToolView.FILES },
                            onNavigateToEditor = { activeView = ActiveToolView.EDITOR },
                            onNavigateToTerminal = { activeView = ActiveToolView.TERMINAL }
                        )
                    }

                    ActiveToolView.FILES -> {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    navigationIcon = {
                                        IconButton(onClick = { activeView = ActiveToolView.CHAT }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = stringResource(R.string.nav_back_to_chat),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    },
                                    title = {
                                        Text(
                                            text = stringResource(R.string.nav_item_files),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    actions = {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            IconButton(onClick = { activeView = ActiveToolView.CHAT }) {
                                                Icon(
                                                    imageVector = Icons.Default.Folder,
                                                    contentDescription = stringResource(R.string.nav_item_files_active),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        IconButton(onClick = { activeView = ActiveToolView.EDITOR }) {
                                            Icon(
                                                imageVector = Icons.Default.Code,
                                                contentDescription = stringResource(R.string.nav_item_editor),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        IconButton(onClick = { activeView = ActiveToolView.TERMINAL }) {
                                            Icon(
                                                imageVector = Icons.Default.Terminal,
                                                contentDescription = stringResource(R.string.nav_item_terminal),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            },
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.fillMaxSize()
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                FileTree(
                                    rootDirectory = rootDir,
                                    onFileSelected = { openFile(it) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    ActiveToolView.EDITOR -> {
                        val activeTab = openTabs.find { it.id == activeTabId }
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    navigationIcon = {
                                        IconButton(onClick = { activeView = ActiveToolView.CHAT }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = stringResource(R.string.nav_back_to_chat),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    },
                                    title = {
                                        Text(
                                            text = activeTab?.title ?: stringResource(R.string.nav_item_editor),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    actions = {
                                        IconButton(onClick = { activeView = ActiveToolView.FILES }) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = stringResource(R.string.nav_item_files),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            IconButton(onClick = { activeView = ActiveToolView.CHAT }) {
                                                Icon(
                                                    imageVector = Icons.Default.Code,
                                                    contentDescription = stringResource(R.string.nav_item_editor_active),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        IconButton(onClick = { activeView = ActiveToolView.TERMINAL }) {
                                            Icon(
                                                imageVector = Icons.Default.Terminal,
                                                contentDescription = stringResource(R.string.nav_item_terminal),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            },
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.fillMaxSize()
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                if (activeTab != null) {
                                    if (activeTab.isDiffView) {
                                        DiffViewer(
                                            fileName = activeTab.title,
                                            originalText = activeTab.originalContent,
                                            modifiedText = activeTab.content,
                                            onApplyChanges = {
                                                activeTab.file.writeText(activeTab.content)
                                                viewModel.updateEditorContent(activeTab.id, activeTab.content)
                                                viewModel.saveEditorTab(context, activeTab.id)
                                            },
                                            onDiscardChanges = {
                                                viewModel.updateEditorContent(activeTab.id, activeTab.originalContent)
                                            },
                                            onBack = {
                                                viewModel.setEditorTabDiffView(activeTab.id, false)
                                            }
                                        )
                                    } else {
                                        CodeEditor(
                                            tab = activeTab,
                                            onSaveFile = { _, content ->
                                                viewModel.updateEditorContent(activeTab.id, content)
                                                viewModel.saveEditorTab(context, activeTab.id)
                                            },
                                            onCloseTab = {
                                                viewModel.closeEditorTab(activeTab.id)
                                            },
                                            onToggleDiff = {
                                                viewModel.setEditorTabDiffView(activeTab.id, true)
                                            }
                                        )
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(32.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Code,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = stringResource(R.string.editor_empty_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = stringResource(R.string.editor_empty_subtitle),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    ActiveToolView.TERMINAL -> {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    navigationIcon = {
                                        IconButton(onClick = { activeView = ActiveToolView.CHAT }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = stringResource(R.string.nav_back_to_chat),
                                                tint = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    },
                                    title = {
                                        Text(
                                            text = stringResource(R.string.nav_item_terminal),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    },
                                    actions = {
                                        IconButton(onClick = { activeView = ActiveToolView.FILES }) {
                                            Icon(
                                                imageVector = Icons.Default.Folder,
                                                contentDescription = stringResource(R.string.nav_item_files),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        IconButton(onClick = { activeView = ActiveToolView.EDITOR }) {
                                            Icon(
                                                imageVector = Icons.Default.Code,
                                                contentDescription = stringResource(R.string.nav_item_editor),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            IconButton(onClick = { activeView = ActiveToolView.CHAT }) {
                                                Icon(
                                                    imageVector = Icons.Default.Terminal,
                                                    contentDescription = stringResource(R.string.nav_item_terminal_active),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                )
                            },
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            modifier = Modifier.fillMaxSize()
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                TerminalView(modifier = Modifier.fillMaxSize())
                            }
                        }
                    }

                    ActiveToolView.SETTINGS -> {
                        SettingsScreen(
                            viewModel = viewModel,
                            onNavigateBack = { activeView = ActiveToolView.CHAT }
                        )
                    }
                }
            }
        }
    }

    if (showModelDialog) {
        ModelSelectorDialog(
            selectedModel = selectedModel,
            selectedThinking = thinkingBudget,
            userQuotaSummary = userQuotaSummary,
            codeAssistTier = codeAssistTier,
            cliModels = cliModels,
            modelsErrorMessage = quotaErrorMessage,
            onModelSelected = { model, thinking, cliId ->
                viewModel.setModel(context, model, thinking, cliId)
                showModelDialog = false
            },
            onDismissRequest = { showModelDialog = false }
        )
    }
}

@Composable
private fun WorkspaceRailItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    compact: Boolean,
    showLabel: Boolean,
    railWidth: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        animationSpec = tween(durationMillis = 180),
        label = "RailPillColor"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "RailIconTint"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "RailLabelColor"
    )

    val itemWidth = (railWidth - 6.dp).coerceAtLeast(44.dp)
    val pillWidth = (itemWidth - 10.dp).coerceIn(36.dp, 46.dp)
    val pillHeight = when {
        !showLabel -> 30.dp
        compact -> 22.dp
        else -> 26.dp
    }
    val minHeight = when {
        !showLabel -> 34.dp
        compact -> 36.dp
        else -> 46.dp
    }

    Column(
        modifier = modifier
            .width(itemWidth)
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (compact) 2.dp else 3.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = pillColor,
            modifier = Modifier
                .width(pillWidth)
                .height(pillHeight)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(if (compact) 17.dp else 18.dp)
                )
            }
        }
        if (showLabel) {
            Spacer(modifier = Modifier.height(if (compact) 1.dp else 2.dp))
            Text(
                text = label,
                fontSize = if (compact) 8.8.sp else 9.5.sp,
                lineHeight = if (compact) 10.sp else 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
