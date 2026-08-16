package jp.tonbiattack.debuglab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrderServiceTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private NotificationService notificationService;

    @BeforeEach
    void clearObservation() {
        notificationService.clearLastNotificationThread();
    }

    @Test
    void confirm_shouldDispatchNotificationToExecutorThread() {
        String callerThread = Thread.currentThread().getName();

        String result = orderService.confirm("order-1");

        assertEquals("confirmed:order-1", result);
        waitUntilNotificationIsObserved();
        assertTrue(notificationService.lastNotificationThread().startsWith("notification-"),
                "通知はExecutorスレッドで実行されるべき");
        assertTrue(!notificationService.lastNotificationThread().equals(callerThread),
                "通知は呼び出しスレッドで同期実行されるべきではない");
    }

    @Test
    void directProxyCall_runsOnNotificationExecutor() {
        String callerThread = Thread.currentThread().getName();

        notificationService.sendNotification("order-2");

        waitUntilNotificationIsObserved();
        assertTrue(notificationService.lastNotificationThread().startsWith("notification-"));
        assertTrue(!notificationService.lastNotificationThread().equals(callerThread));
    }

    private void waitUntilNotificationIsObserved() {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (notificationService.lastNotificationThread() == null
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(notificationService.lastNotificationThread() != null,
                "非同期通知が制限時間内に観測できなかった");
    }
}
