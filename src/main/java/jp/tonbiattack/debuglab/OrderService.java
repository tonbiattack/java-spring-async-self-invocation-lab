package jp.tonbiattack.debuglab;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final AtomicReference<String> lastNotificationThread = new AtomicReference<>();

    public String confirm(String orderId) {
        sendNotification(orderId);
        return "confirmed:" + orderId;
    }

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
