package me.paxana.abcmailbox.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import me.paxana.abcmailbox.ui.common.longDate
import me.paxana.abcmailbox.data.repo.DirectorySource
import androidx.compose.ui.res.stringResource
import me.paxana.abcmailbox.R
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.Manifest
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import kotlinx.coroutines.launch
import androidx.navigation.toRoute
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.ui.account.AccountScreen
import me.paxana.abcmailbox.ui.account.ChangePasswordScreen
import me.paxana.abcmailbox.ui.auth.ClaimScreen
import me.paxana.abcmailbox.ui.auth.RecoverScreen
import me.paxana.abcmailbox.ui.auth.RecoveryCodeScreen
import me.paxana.abcmailbox.ui.auth.LoginScreen
import me.paxana.abcmailbox.ui.directory.DirectoryHomeScreen
import me.paxana.abcmailbox.ui.group.AddWriterScreen
import me.paxana.abcmailbox.ui.group.HandoffScreen
import me.paxana.abcmailbox.ui.group.LetterWorkScreen
import me.paxana.abcmailbox.ui.directory.FacilitiesScreen
import me.paxana.abcmailbox.ui.directory.FacilityScreen
import me.paxana.abcmailbox.ui.directory.GroupScreen
import me.paxana.abcmailbox.ui.directory.GroupsScreen
import me.paxana.abcmailbox.ui.directory.PrisonerScreen
import me.paxana.abcmailbox.ui.directory.PrisonersScreen
import me.paxana.abcmailbox.ui.letters.ComposeScreen
import me.paxana.abcmailbox.ui.letters.InboxScreen
import me.paxana.abcmailbox.ui.letters.ThreadScreen
import kotlin.reflect.KClass

private data class Tab(val route: Any, val routeClass: KClass<*>, @androidx.annotation.StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
  Tab(DirectoryGraph, DirectoryGraph::class, R.string.tab_directory, Icons.Outlined.MenuBook),
  Tab(InboxGraph, InboxGraph::class, R.string.tab_inbox, Icons.Outlined.Mail),
  Tab(AccountRoute, AccountRoute::class, R.string.tab_account, Icons.Outlined.Person),
)

/**
 * One Activity, one NavHost, three tabs. Sign-in is a full-screen destination
 * pushed on top of whichever tab asked for it; when the session becomes
 * signed-in the login screen pops itself.
 */
private const val ACCOUNT_UNKNOWN = Int.MIN_VALUE
private const val NO_ACCOUNT = -1

@Composable
fun AppShell(viewModel: SessionViewModel = hiltViewModel()) {
  val sessionState by viewModel.state.collectAsStateWithLifecycle()

  // When the account changes (sign-out, an expired session, or a different person signing in), nothing the
  // previous account had open may stay reachable: not a thread on the Inbox tab's saved back stack, not a
  // half-written letter in a ViewModel. The whole navigation state is thrown away and built again, which is
  // what `key` does to everything inside it when its value changes.
  //
  // (Until 19 Sep 2026 this called `clearBackStack` for each tab instead. For the tab that is the start
  // destination that removes the start destination itself, and the Directory tab then led to the Account page.)
  val accountId = (sessionState as? SessionState.SignedIn)?.session?.user?.id ?: NO_ACCOUNT
  var lastAccountId by rememberSaveable { mutableIntStateOf(ACCOUNT_UNKNOWN) }
  var generation by rememberSaveable { mutableIntStateOf(0) }
  LaunchedEffect(accountId, sessionState is SessionState.Loading) {
    if (sessionState is SessionState.Loading) return@LaunchedEffect
    // Signing in from signed-out changes nothing that was private; every other change does.
    if (lastAccountId != ACCOUNT_UNKNOWN && lastAccountId != NO_ACCOUNT && lastAccountId != accountId) generation++
    lastAccountId = accountId
  }
  key(generation) { Shell(viewModel, sessionState, landOnAccount = generation > 0) }
}

@Composable
private fun Shell(viewModel: SessionViewModel, sessionState: SessionState, landOnAccount: Boolean) {
  val navController = rememberNavController()
  // A rebuilt shell starts on the Directory like a fresh launch. Whoever just signed out (or was signed
  // out) is better served by the Account page, where signing in again is one tap. Once, not on every rotation.
  var landed by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    if (landOnAccount && !landed) navController.navigate(AccountRoute) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true }
    landed = true
  }
  val scope = rememberCoroutineScope()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val destination = backStackEntry?.destination
  val fullScreen = listOf(LoginRoute::class, ClaimRoute::class, RecoverRoute::class, RecoveryCodeRoute::class)
  val showBars = fullScreen.none { destination?.hasRoute(it) == true }
  val snackbar = remember { SnackbarHostState() }
  // Snackbars are shown from callbacks, where there is no composition to read resources in, so the
  // sentences are resolved here, where there is.
  val sessionEnded = stringResource(R.string.notice_session_ended)
  val passwordChangedElsewhereOut = stringResource(R.string.notice_password_changed_elsewhere_signed_out)
  val accountClaimed = stringResource(R.string.notice_account_claimed)
  val passwordChangedSignedIn = stringResource(R.string.notice_password_changed_signed_in)

  val pendingCode by viewModel.pendingRecoveryCode.collectAsStateWithLifecycle()
  val keysLocked by viewModel.keysLocked.collectAsStateWithLifecycle()
  val mode by viewModel.mode.collectAsStateWithLifecycle()
  val directorySource by viewModel.directorySource.collectAsStateWithLifecycle()
  val unsentCount by viewModel.unsentCount.collectAsStateWithLifecycle()
  val letterQueued = stringResource(R.string.notice_letter_queued)
  // Android 13+ asks the user before an app may post notifications. Asked here, the first time it matters
  // (a letter was just queued and its fate will be decided while they are not looking), not at first launch.
  val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

  LaunchedEffect(Unit) {
    viewModel.expired.collect { snackbar.showSnackbar(sessionEnded) }
  }

  // A recovery code was just created (first sign-in on an end-to-end server, or a claim):
  // it takes over the screen until the writer confirms they saved it.
  LaunchedEffect(pendingCode) {
    if (pendingCode != null && destination?.hasRoute(RecoveryCodeRoute::class) != true) navController.navigate(RecoveryCodeRoute) { launchSingleTop = true }
  }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    bottomBar = {
      AnimatedVisibility(visible = showBars) {
        NavigationBar {
          tabs.forEach { tab ->
            val selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
            NavigationBarItem(
              selected = selected,
              onClick = {
                navController.navigate(tab.route) {
                  // Standard bottom-bar behaviour: one back stack per tab,
                  // state kept when switching, no duplicate copies of a tab.
                  popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                  launchSingleTop = true
                  restoreState = true
                }
              },
              icon = {
                // The Inbox tab counts unsent letters, so they are not forgotten on another tab.
                if (tab.routeClass == InboxGraph::class && unsentCount > 0) BadgedBox(badge = { Badge { Text(unsentCount.toString()) } }) { Icon(tab.icon, contentDescription = null) }
                else Icon(tab.icon, contentDescription = null)
              },
              label = { Text(stringResource(tab.label)) },
            )
          }
        }
      }
    },
  ) { innerPadding ->
    // The banner belongs to the screens that read the directory; a letter thread that fails offline says so itself.
    val onDirectoryScreen = destination?.hierarchy?.any { it.hasRoute(DirectoryGraph::class) } == true ||
      destination?.hasRoute(ComposeRoute::class) == true || destination?.hasRoute(PickPrisonerRoute::class) == true
    val saved = directorySource as? DirectorySource.Saved
    Column(Modifier.padding(innerPadding)) {
      if (saved != null && onDirectoryScreen) SavedCopyBanner(saved.at)
    NavHost(
      navController = navController,
      startDestination = DirectoryGraph,
      modifier = Modifier.weight(1f),
    ) {
      navigation<DirectoryGraph>(startDestination = DirectoryHomeRoute) {
        composable<DirectoryHomeRoute> {
          DirectoryHomeScreen(
            onPrisoners = { navController.navigate(PrisonersRoute) },
            onFacilities = { navController.navigate(FacilitiesRoute) },
            onGroups = { navController.navigate(GroupsRoute) },
            onPrisoner = { navController.navigate(PrisonerRoute(it)) },
          )
        }
        composable<PrisonersRoute> {
          PrisonersScreen(onBack = { navController.popBackStack() }, onPrisoner = { navController.navigate(PrisonerRoute(it)) })
        }
        composable<PrisonerRoute> {
          PrisonerScreen(
            onBack = { navController.popBackStack() },
            onFacility = { navController.navigate(FacilityRoute(it)) },
            onGroup = { navController.navigate(GroupRoute(it)) },
            onWrite = { navController.navigate(ComposeRoute(it)) },
          )
        }
        composable<FacilitiesRoute> {
          FacilitiesScreen(onBack = { navController.popBackStack() }, onFacility = { navController.navigate(FacilityRoute(it)) })
        }
        composable<FacilityRoute> {
          FacilityScreen(
            onBack = { navController.popBackStack() },
            onPrisoner = { navController.navigate(PrisonerRoute(it)) },
            onGroup = { navController.navigate(GroupRoute(it)) },
          )
        }
        composable<GroupsRoute> {
          GroupsScreen(onBack = { navController.popBackStack() }, onGroup = { navController.navigate(GroupRoute(it)) })
        }
        composable<GroupRoute> {
          GroupScreen(
            onBack = { navController.popBackStack() },
            onPrisoner = { navController.navigate(PrisonerRoute(it)) },
            onFacility = { navController.navigate(FacilityRoute(it)) },
          )
        }
      }
      navigation<InboxGraph>(startDestination = InboxRoute) {
        composable<InboxRoute> {
          InboxScreen(
            sessionState = sessionState,
            keysLocked = keysLocked,
            onSignIn = { navController.navigate(LoginRoute) },
            onThread = { navController.navigate(ThreadRoute(it)) },
            onNewLetter = { navController.navigate(PickPrisonerRoute()) },
            onQueueLetter = { navController.navigate(LetterWorkRoute(it)) },
            onAddWriter = { navController.navigate(AddWriterRoute) },
            onGroupLetter = { writerId, writerName -> navController.navigate(PickPrisonerRoute(writerId, writerName)) },
            onHandoff = { navController.navigate(HandoffRoute(it.id, it.name)) },
            onGroupKey = { navController.navigate(GroupKeyRoute) },
            onEditQueued = { q -> navController.navigate(ComposeRoute(q.payload.prisonerId, writerId = q.payload.asWriterId.takeIf { !q.payload.fromPrisoner }, writerName = q.payload.writingAs, replyForUserId = q.payload.asWriterId.takeIf { q.payload.fromPrisoner }, outboxId = q.id)) },
          )
        }
        composable<PickPrisonerRoute> { entry ->
          val pick = entry.toRoute<PickPrisonerRoute>()
          PrisonersScreen(
            title = pick.writerName?.let { stringResource(R.string.title_write_as_to, it) } ?: stringResource(R.string.title_write_to),
            onBack = { navController.popBackStack() },
            onPrisoner = { navController.navigate(ComposeRoute(it, writerId = pick.writerId, writerName = pick.writerName)) { popUpTo<PickPrisonerRoute> { inclusive = true } } },
          )
        }
        composable<LetterWorkRoute> {
          LetterWorkScreen(onBack = { navController.popBackStack() }, onThread = { navController.navigate(ThreadRoute(it)) })
        }
        composable<AddWriterRoute> {
          AddWriterScreen(
            onBack = { navController.popBackStack() },
            onDone = { writer, thenWrite ->
              navController.popBackStack()
              if (thenWrite) navController.navigate(PickPrisonerRoute(writer.id, writer.name))
              else scope.launch { snackbar.showSnackbar("${writer.name} added.") }
            },
          )
        }
        composable<HandoffRoute> { HandoffScreen(onBack = { navController.popBackStack() }) }
        composable<GroupKeyRoute> { me.paxana.abcmailbox.ui.group.GroupKeyScreen(onBack = { navController.popBackStack() }) }
      }
      // Reachable from both tabs, so they live outside either graph.
      composable<ThreadRoute> {
        ThreadScreen(
          onBack = { navController.popBackStack() },
          onPrisoner = { navController.navigate(PrisonerRoute(it)) },
          onWrite = { navController.navigate(ComposeRoute(it)) },
          onEdit = { prisonerId, messageId -> navController.navigate(ComposeRoute(prisonerId, messageId)) },
          onGroupWrite = { prisonerId, writerId, writerName -> navController.navigate(ComposeRoute(prisonerId, writerId = writerId, writerName = writerName)) },
          onRecordReply = { prisonerId, writerUserId -> navController.navigate(ComposeRoute(prisonerId, replyForUserId = writerUserId)) },
        )
      }
      composable<ComposeRoute> {
        ComposeScreen(
          sessionState = sessionState,
          onSignIn = { navController.navigate(LoginRoute) },
          onBack = { navController.popBackStack() },
          onQueued = {
            navController.popBackStack()
            scope.launch { snackbar.showSnackbar(letterQueued) }
            if (Build.VERSION.SDK_INT >= 33) askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
          },
          onSent = { chatId ->
            // Opened from that very thread: go back to it (it reloads on resume) rather than stacking a second copy.
            val from = navController.previousBackStackEntry
            val cameFromThisThread = from != null && from.destination.hasRoute<ThreadRoute>() && from.toRoute<ThreadRoute>().chatId == chatId
            if (cameFromThisThread) navController.popBackStack()
            else navController.navigate(ThreadRoute(chatId)) { popUpTo<ComposeRoute> { inclusive = true } }
          },
        )
      }
      composable<AccountRoute> {
        AccountScreen(
          sessionState = sessionState,
          mode = mode,
          onSignIn = { navController.navigate(LoginRoute) },
          onChangePassword = { navController.navigate(ChangePasswordRoute) },
        )
      }
      composable<ChangePasswordRoute> {
        ChangePasswordScreen(
          onBack = { navController.popBackStack() },
          onDone = {
            navController.popBackStack()
            scope.launch { snackbar.showSnackbar(passwordChangedElsewhereOut) }
          },
        )
      }
      composable<LoginRoute> {
        LoginScreen(
          sessionState = sessionState,
          onSignedIn = { navController.popBackStack() },
          onCancel = { navController.popBackStack() },
          onClaim = { navController.navigate(ClaimRoute()) },
          onForgot = { navController.navigate(RecoverRoute) },
        )
      }
      // abcmailbox://claim?token=… opens this screen with the token filled in. The https
      // form (App Links) waits for a domain; see docs/PLAN.md section 8.
      composable<ClaimRoute>(deepLinks = listOf(navDeepLink<ClaimRoute>(basePath = "abcmailbox://claim"))) {
        ClaimScreen(
          sessionState = sessionState,
          mode = mode,
          onClaimed = {
            navController.navigate(InboxGraph) { popUpTo(navController.graph.findStartDestination().id); launchSingleTop = true }
            scope.launch { snackbar.showSnackbar(accountClaimed) }
          },
          onBack = { if (!navController.popBackStack()) navController.navigate(DirectoryGraph) },
        )
      }
      composable<RecoverRoute> {
        RecoverScreen(
          sessionState = sessionState,
          onBack = { navController.popBackStack() },
          onClaim = { navController.navigate(ClaimRoute()) },
          onRecovered = {
            navController.navigate(InboxGraph) { popUpTo(navController.graph.findStartDestination().id); launchSingleTop = true }
            scope.launch { snackbar.showSnackbar(passwordChangedSignedIn) }
          },
        )
      }
      composable<RecoveryCodeRoute> {
        // One path owns the pop: confirming clears the code, and the cleared code pops the screen.
        // Popping in both places popped twice, because this composable is still alive, with a
        // null code, while it animates out.
        val code = pendingCode
        if (code == null) LaunchedEffect(Unit) { navController.popBackStack<RecoveryCodeRoute>(inclusive = true) }
        else RecoveryCodeScreen(code = code, onSaved = { viewModel.recoveryCodeSaved() })
      }
    }
    } // Column: banner above the NavHost
  }
}

/** Said plainly and without alarm: the directory still works, it is just as of a date. */
@Composable
private fun SavedCopyBanner(at: java.time.Instant) {
  Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
    Text(
      stringResource(R.string.offline_banner, at.longDate()),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
  }
}
