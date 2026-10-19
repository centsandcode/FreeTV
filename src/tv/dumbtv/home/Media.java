package tv.dumbtv.home;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the system will hand Home the active media sessions
 * ({@code MediaSessionManager.getActiveSessions} needs an enabled notification listener).
 * It reads no notifications. Off until enabled over ADB:
 * {@code adb shell cmd notification allow_listener tv.dumbtv.home/tv.dumbtv.home.Media}
 */
public final class Media extends NotificationListenerService {}
