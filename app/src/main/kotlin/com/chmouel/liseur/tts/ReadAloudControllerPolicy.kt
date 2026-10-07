package com.chmouel.liseur.tts

/**
 * Who may drive read-aloud's media session.
 *
 * The service is not exported, but a session token can still reach other
 * apps, and every play is a request billed to the reader's own key. Only
 * this app, the platform's trusted controllers (System UI, the lock
 * screen, Bluetooth and headset buttons) and Media3's own notification
 * controller get in.
 */
object ReadAloudControllerPolicy {
    fun accepts(controllerUid: Int, ownUid: Int, trusted: Boolean, notificationController: Boolean): Boolean =
        controllerUid == ownUid || trusted || notificationController
}
