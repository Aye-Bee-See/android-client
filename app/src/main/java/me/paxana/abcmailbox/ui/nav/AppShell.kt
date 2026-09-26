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
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import me.paxana.abcmailbox.data.activity.AndroidActivityNotifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import me.paxana.abcmailbox.ui.common.LocalNewsTick
import me.paxana.abcmailbox.text.rememberStrings
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
import me.paxana.abcmailbox.ui.account.FarewellDialog
import me.paxana.abcmailbox.ui.account.DeleteAccountScreen
import me.paxana.abcmailbox.ui.auth.ClaimScreen
import me.paxana.abcmailbox.ui.auth.JoinScreen
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

  // Outside `key`, so it survives the rebuild that the deletion itself causes.
  val farewell by viewModel.farewell.collectAsStateWithLifecycle()
  farewell?.let { FarewellDialog(it, onClose = viewModel::farewellSeen) }
}

@Composable
private fun Shell(viewModel: SessionViewModel, sessionState: SessionState, landOnAccount: Boolean) {
  val navController = rememberNavController()
  // A rebuilt shell starts on the Directory like a fresh launch. Whoever just signed out (or was signed
  // out) is better served by the Account page, where signing in again is one tap. Once, not on every rotation.
  // Navigation reads the launching intent by itself, but not one that arrives while the app is already open
  // (a tapped notification). The Activity passes those on here.
  val activity = LocalActivity.current as? ComponentActivity
  DisposableEffect(activity, navController) {
    val listener = androidx.core.util.Consumer<Intent> { intent -> navController.handleDeepLink(intent) }
    activity?.addOnNewIntentListener(listener)
    onDispose { activity?.removeOnNewIntentListener(listener) }
  }
  val snackbar = remember { SnackbarHostState() }
  // News from the server while the app is on screen. Collected only while the app is visible (STARTED): that is
  // how the repository knows somebody is looking, and shows a system notification when nobody is.
  var newsTick by remember { mutableIntStateOf(0) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val newsStrings = rememberStrings()
  val viewLabel = stringResource(R.string.action_view)
  LaunchedEffect(lifecycleOwner, navController) {
    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
      viewModel.arrivals.collect { fresh ->
        newsTick++ // screens showing lists or a conversation reload themselves
        val chat = fresh.mapNotNull { it.chatId }.distinct().singleOrNull()
        val lookingAtIt = chat != null && navController.currentBackStackEntry?.let { e -> e.destination.hasRoute<ThreadRoute>() && e.toRoute<ThreadRoute>().chatId == chat } == true
        if (lookingAtIt) { viewModel.inboxSeen(); return@collect } // it just appeared in front of them; nothing to announce
        launch {
          val lines = fresh.map { it.sentence(newsStrings) }.distinct()
          val text = lines.first() + if (lines.size > 1) " " + newsStrings.plural(R.plurals.activity_and_more, lines.size - 1) else ""
          if (snackbar.showSnackbar(text, actionLabel = viewLabel, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
            if (chat != null) navController.navigate(ThreadRoute(chat)) { launchSingleTop = true }
            else navController.navigate(InboxGraph) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
          }
        }
      }
    }
  }
  var landed by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    if (landOnAccount && !landed) navController.navigate(AccountRoute) { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true }
    landed = true
  }
  val scope = rememberCoroutineScope()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val destination = backStackEntry?.destination
  val fullScreen = listOf(LoginRoute::class, ClaimRoute::class, JoinRoute::class, RecoverRoute::class, RecoveryCodeRoute::class)
  val showBars = fullScreen.none { destination?.hasRoute(it) == true }
  // Snackbars are shown from callbacks, where there is no composition to read resources in, so the
  // sentences are resolved here, where there is.
  val sessionEnded = stringResource(R.string.notice_session_ended)
  val passwordChangedElsewhereOut = stringResource(R.string.notice_password_changed_elsewhere_signed_out)
  val accountClaimed = stringResource(R.string.notice_account_claimed)
  val joined = stringResource(R.string.notice_joined)
  val passwordChangedSignedIn = stringResource(R.string.notice_password_changed_signed_in)

  val pendingCode by viewModel.pendingRecoveryCode.collectAsStateWithLifecycle()
  val lettersCaughtUp by viewModel.lettersCaughtUp.collectAsStateWithLifecycle()
  val keysLocked by viewModel.keysLocked.collectAsStateWithLifecycle()
  val mode by viewModel.mode.collectAsStateWithLifecycle()
  val directorySource by viewModel.directorySource.collectAsStateWithLifecycle()
  val unsentCount by viewModel.unsentCount.collectAsStateWithLifecycle()
  val unreadActivity by viewModel.unreadActivity.collectAsStateWithLifecycle()
  // What needs attention on the Inbox tab: letters that have not gone yet, and news that has not been seen yet.
  val inboxBadge = unsentCount + unreadActivity
  val letterQueued = stringResource(R.string.notice_letter_queued)
  val writerAdded = stringResource(R.string.notice_writer_added) // formatted in the callback, where the name is known
  // Android 13+ asks the user before an app may post notifications. Asked here, the first time it matters
  // (a letter was just queued and its fate will be decided while they are not looking), not at first launch.
  val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

  LaunchedEffect(Unit) {
    viewModel.expired.collect { snackbar.showSnackbar(sessionEnded) }
  }

  // A recovery code was just created (first sign-in on an end-to-end server, a claim, a join): it takes over the
  // screen until the writer confirms they saved it. Keyed on the destination too: the screen that made the account
  // leaves for the Inbox in the same frame, popping to the start destination, which took this screen with it (seen
  // on the emulator, 23 Sep 2026, joining with an invite code). Now the code is put back on top wherever it lands.
  LaunchedEffect(pendingCode, destination) {
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
                if (tab.routeClass == InboxGraph::class && inboxBadge > 0) BadgedBox(badge = { Badge { Text(inboxBadge.toString()) } }) { Icon(tab.icon, contentDescription = null) }
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
    CompositionLocalProvider(LocalNewsTick provides newsTick) {
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
        // Opened by the activity notification when its news is about more than one conversation.
        composable<InboxRoute>(deepLinks = listOf(navDeepLink<InboxRoute>(basePath = AndroidActivityNotifier.INBOX_LINK))) {
          // Looking at the Inbox is what "read" means for the feed.
          LaunchedEffect(sessionState) { if (sessionState is SessionState.SignedIn) viewModel.inboxSeen() }
          // A group member's work arrives as notifications ("a letter is waiting to be printed"), so they are asked as soon as they have an inbox.
          LaunchedEffect(sessionState) { if (Build.VERSION.SDK_INT >= 33 && (sessionState as? SessionState.SignedIn)?.session?.user?.isStaff == true) askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }
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
            onReplyArrived = { navController.navigate(RecordReplyRoute) },
            onEditQueued = { q -> navController.navigate(ComposeRoute(q.payload.prisonerId, writerId = q.payload.asWriterId.takeIf { !q.payload.fromPrisoner }, writerName = q.payload.writingAs, replyForUserId = q.payload.asWriterId.takeIf { q.payload.fromPrisoner }, outboxId = q.id, reference = q.payload.reference)) },
          )
        }
        composable<PickPrisonerRoute> { entry ->
          val pick = entry.toRoute<PickPrisonerRoute>()
          PrisonersScreen(
            title = if (pick.replyFor) stringResource(R.string.title_record_reply) else pick.writerName?.let { stringResource(R.string.title_write_as_to, it) } ?: stringResource(R.string.title_write_to),
            onBack = { navController.popBackStack() },
            onPrisoner = {
              val route = if (pick.replyFor) ComposeRoute(it, replyForUserId = pick.writerId, writerName = pick.writerName) else ComposeRoute(it, writerId = pick.writerId, writerName = pick.writerName)
              navController.navigate(route) { popUpTo<PickPrisonerRoute> { inclusive = true } }
            },
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
              else scope.launch { snackbar.showSnackbar(writerAdded.format(writer.name)) }
            },
          )
        }
        composable<HandoffRoute> { HandoffScreen(onBack = { navController.popBackStack() }) }
        // A reply came in the post (API PR #120): by its number, or by the writer's name.
        composable<RecordReplyRoute> {
          me.paxana.abcmailbox.ui.group.RecordReplyScreen(
            onBack = { navController.popBackStack() },
            onRecord = { prisonerId, writerId, reference -> navController.navigate(ComposeRoute(prisonerId, replyForUserId = writerId, reference = reference)) { popUpTo<RecordReplyRoute> { inclusive = true } } },
            onThread = { navController.navigate(ThreadRoute(it)) },
            onWriter = { writerId, name -> navController.navigate(PickPrisonerRoute(writerId, name, replyFor = true)) },
          )
        }
        composable<GroupKeyRoute> { me.paxana.abcmailbox.ui.group.GroupKeyScreen(onBack = { navController.popBackStack() }) }
      }
      // Reachable from both tabs, so they live outside either graph.
      // Opened by the activity notification when all its news is about one conversation: abcmailbox://open/thread/<chat id>.
      composable<ThreadRoute>(deepLinks = listOf(navDeepLink<ThreadRoute>(basePath = AndroidActivityNotifier.THREAD_LINK))) {
        LaunchedEffect(Unit) { viewModel.inboxSeen() }
        ThreadScreen(
          onBack = { navController.popBackStack() },
          onPrisoner = { navController.navigate(PrisonerRoute(it)) },
          onWrite = { navController.navigate(ComposeRoute(it)) },
          onEdit = { prisonerId, messageId -> navController.navigate(ComposeRoute(prisonerId, messageId)) },
          onGroupWrite = { prisonerId, writerId, writerName -> navController.navigate(ComposeRoute(prisonerId, writerId = writerId, writerName = writerName)) },
          onRecordReply = { prisonerId, writerUserId -> navController.navigate(ComposeRoute(prisonerId, replyForUserId = writerUserId)) },
          onSendAgain = { prisonerId, letterId, replacesHeld, writerId, writerName ->
            navController.navigate(ComposeRoute(prisonerId, writerId = writerId, writerName = writerName, resendOf = letterId.takeIf { !replacesHeld }, replacesHeld = letterId.takeIf { replacesHeld }))
          },
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
            // The letter's fate (printed, mailed, a reply) is decided over the coming weeks: a good moment to ask. A no-op once answered.
            if (Build.VERSION.SDK_INT >= 33) askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
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
          onDeleteAccount = { navController.navigate(DeleteAccountRoute) },
          onGroupNumbers = { navController.navigate(GroupNumbersRoute) },
          onInviteCodes = { navController.navigate(InviteCodesRoute) },
          onPenName = { navController.navigate(PenNameRoute) },
        )
      }
      composable<PenNameRoute> { me.paxana.abcmailbox.ui.account.PenNameScreen(onBack = { navController.popBackStack() }) }
      composable<GroupNumbersRoute> { me.paxana.abcmailbox.ui.group.GroupNumbersScreen(onBack = { navController.popBackStack() }) }
      composable<InviteCodesRoute> { me.paxana.abcmailbox.ui.group.InviteCodesScreen(onBack = { navController.popBackStack() }) }
      composable<DeleteAccountRoute> { DeleteAccountScreen(onBack = { navController.popBackStack() }, onGroupKey = { navController.navigate(GroupKeyRoute) }) }
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
          onJoin = { navController.navigate(JoinRoute()) },
          onForgot = { navController.navigate(RecoverRoute) },
        )
      }
      // The slip's QR code is https://letters.support/join?code=…, which opens here with the code filled in; so does
      // abcmailbox://join?code=…. The https link is verified (App Links) only once the domain hosts assetlinks.json.
      composable<JoinRoute>(deepLinks = listOf(navDeepLink<JoinRoute>(basePath = "https://${me.paxana.abcmailbox.domain.InviteCode.LINK_HOST}${me.paxana.abcmailbox.domain.InviteCode.LINK_PATH}"), navDeepLink<JoinRoute>(basePath = "abcmailbox://join"))) {
        JoinScreen(
          sessionState = sessionState,
          mode = mode,
          onJoined = {
            navController.navigate(InboxGraph) { popUpTo(navController.graph.findStartDestination().id); launchSingleTop = true }
            scope.launch { snackbar.showSnackbar(joined) }
          },
          onBack = { if (!navController.popBackStack()) navController.navigate(DirectoryGraph) },
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
        else RecoveryCodeScreen(code = code, lettersCaughtUp = lettersCaughtUp, onSaved = { viewModel.recoveryCodeSaved() })
      }
    }
    } // Column: banner above the NavHost
    } // LocalNewsTick
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
