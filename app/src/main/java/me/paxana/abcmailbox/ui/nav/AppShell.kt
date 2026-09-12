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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.navigation.toRoute
import me.paxana.abcmailbox.data.session.SessionState
import me.paxana.abcmailbox.ui.account.AccountScreen
import me.paxana.abcmailbox.ui.auth.LoginScreen
import me.paxana.abcmailbox.ui.directory.DirectoryHomeScreen
import me.paxana.abcmailbox.ui.directory.FacilitiesScreen
import me.paxana.abcmailbox.ui.directory.FacilityScreen
import me.paxana.abcmailbox.ui.directory.GroupScreen
import me.paxana.abcmailbox.ui.directory.GroupsScreen
import me.paxana.abcmailbox.ui.directory.PrisonerScreen
import me.paxana.abcmailbox.ui.directory.PrisonersScreen
import me.paxana.abcmailbox.ui.letters.InboxPlaceholder
import kotlin.reflect.KClass

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
  Tab(DirectoryGraph, DirectoryGraph::class, "Directory", Icons.Outlined.MenuBook),
  Tab(InboxRoute, InboxRoute::class, "Inbox", Icons.Outlined.Mail),
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
  val backStackEntry by navController.currentBackStackEntryAsState()
  val destination = backStackEntry?.destination
  val showBars = destination?.hasRoute(LoginRoute::class) != true

  Scaffold(
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
      composable<InboxRoute> {
        InboxPlaceholder(sessionState = sessionState, onSignIn = { navController.navigate(LoginRoute) })
      }
      composable<AccountRoute> {
        AccountScreen(sessionState = sessionState, onSignIn = { navController.navigate(LoginRoute) })
      }
      composable<LoginRoute> {
        LoginScreen(
          sessionState = sessionState,
          onSignedIn = { navController.popBackStack() },
          onCancel = { navController.popBackStack() },
        )
      }
    }
  }
}
