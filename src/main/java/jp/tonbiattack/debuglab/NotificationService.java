package jp.tonbiattack.debuglab;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final AtomicReference<String> lastNotificationThread = new AtomicReference<>();

    @Async("notificationExecutor")
    public void sendNotification(String orderId) {
        lastNotificationThread.set(Thread.currentThread().getName());
    }

    public String lastNotificationThread() {
        return lastNotificationThread.get();
    }

    void clearLastNotificationThread() {
        lastNotificationThread.set(null);
    }
}
