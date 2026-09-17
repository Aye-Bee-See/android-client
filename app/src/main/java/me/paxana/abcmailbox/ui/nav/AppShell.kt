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
import androidx.compose.runtime.LaunchedEffect
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

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
  Tab(DirectoryGraph, DirectoryGraph::class, "Directory", Icons.Outlined.MenuBook),
  Tab(InboxGraph, InboxGraph::class, "Inbox", Icons.Outlined.Mail),
  Tab(AccountRoute, AccountRoute::class, "Account", Icons.Outlined.Person),
)

/**
 * One Activity, one NavHost, three tabs. Sign-in is a full-screen destination
 * pushed on top of whichever tab asked for it; when the session becomes
 * signed-in the login screen pops itself.
 */
@Composable
fun AppShell(viewModel: SessionViewModel = hiltViewModel()) {
  val sessionState by viewModel.state.collectAsStateWithLifecycle()
  val navController = rememberNavController()
  val scope = rememberCoroutineScope()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val destination = backStackEntry?.destination
  val fullScreen = listOf(LoginRoute::class, ClaimRoute::class, RecoverRoute::class, RecoveryCodeRoute::class)
  val showBars = fullScreen.none { destination?.hasRoute(it) == true }
  val snackbar = remember { SnackbarHostState() }

  val pendingCode by viewModel.pendingRecoveryCode.collectAsStateWithLifecycle()
  val keysLocked by viewModel.keysLocked.collectAsStateWithLifecycle()
  val mode by viewModel.mode.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    viewModel.expired.collect { snackbar.showSnackbar("Your session ended. Please sign in again.") }
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
              icon = { Icon(tab.icon, contentDescription = null) },
              label = { Text(tab.label) },
            )
          }
        }
      }
    },
  ) { innerPadding ->
    NavHost(
      navController = navController,
      startDestination = DirectoryGraph,
      modifier = Modifier.padding(innerPadding),
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
          )
        }
        composable<PickPrisonerRoute> { entry ->
          val pick = entry.toRoute<PickPrisonerRoute>()
          PrisonersScreen(
            title = pick.writerName?.let { "Write as $it to…" } ?: "Write to…",
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
            scope.launch { snackbar.showSnackbar("Password changed. Other devices were signed out.") }
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
            scope.launch { snackbar.showSnackbar("Account claimed. You are signed in.") }
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
            scope.launch { snackbar.showSnackbar("Password changed. You are signed in.") }
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
  }
}
