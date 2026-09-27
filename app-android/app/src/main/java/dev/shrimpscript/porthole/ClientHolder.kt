package dev.shrimpscript.porthole

import dev.shrimpscript.porthole.net.PortholeClient

/**
 * The one live client, reachable from outside the composition - a notification's
 * inline Reply arrives in a BroadcastReceiver, which has no view model. Set by the view
 * model for as long as it lives; null means "open the app to reply".
 */
object ClientHolder {
    @Volatile var client: PortholeClient? = null
    /** The fleet, so a reply to a session on a second computer reaches that computer. */
    @Volatile var fleet: dev.shrimpscript.porthole.net.Fleet? = null
}
